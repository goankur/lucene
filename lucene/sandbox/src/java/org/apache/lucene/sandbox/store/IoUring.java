/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.sandbox.store;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * io_uring O_DIRECT batched vector reader via {@code liburing-ffi} (pure FFM; liburing owns the
 * ring mmap, memory ordering, and arch specifics). A reader borrows a ring from a bounded pool and
 * submits a whole shortlist as one batched submit-and-wait, so the kernel supplies the read
 * concurrency and no read-thread pool is needed. Package-private engine behind {@link
 * IoUringDirectory}.
 */
@SuppressWarnings("restricted") // FFM downcalls to libc + liburing-ffi
final class IoUring implements Closeable {
  private static final int BLK = 4096;
  private static final int O_RDONLY = 0;

  private static final int F_GETFL = 3; // same on every Linux architecture

  /**
   * O_DIRECT open flag, which is one of the few {@code open(2)} flags that is not identical across
   * Linux architectures. A wrong guess here would be silently harmful — {@code open} can succeed
   * while ignoring the flag, leaving us with ordinary buffered reads — so {@link #openDirect}
   * verifies with {@code fcntl(F_GETFL)} that the kernel really honoured it and fails if not.
   */
  private static final int O_DIRECT =
      switch (System.getProperty("os.arch", "")) {
        case "aarch64" -> 0x10000; // arm64 asm/fcntl.h
        case "ppc64", "ppc64le" -> 0x20000; // powerpc
        default -> 0x4000; // x86_64, s390x, and the asm-generic default
      };

  /** libc + liburing-ffi bindings; <clinit> throws (caught by {@link #isAvailable}) if absent. */
  private static final class Native {
    static final Linker L = Linker.nativeLinker();
    static final SymbolLookup LIBC = L.defaultLookup();
    static final SymbolLookup URING =
        SymbolLookup.libraryLookup("liburing-ffi.so.2", Arena.global());

    static MethodHandle libc(String n, FunctionDescriptor fd) {
      return L.downcallHandle(
          LIBC.find(n)
              .orElseThrow(
                  () -> new UnsupportedOperationException("libc has no symbol '" + n + "'")),
          fd);
    }

    static MethodHandle u(String n, FunctionDescriptor fd) {
      return L.downcallHandle(
          URING
              .find(n)
              .orElseThrow(
                  () ->
                      new UnsupportedOperationException("liburing-ffi has no symbol '" + n + "'")),
          fd);
    }

    static final MethodHandle MH$open =
        libc("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    static final MethodHandle MH$close = libc("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    /** {@code fcntl} is variadic; F_GETFL passes no variadic argument. */
    static final MethodHandle MH$fcntl =
        L.downcallHandle(
            LIBC.find("fcntl")
                .orElseThrow(() -> new UnsupportedOperationException("libc has no symbol 'fcntl'")),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT),
            Linker.Option.firstVariadicArg(2));

    static final MethodHandle MH$io_uring_queue_init =
        u("io_uring_queue_init", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    static final MethodHandle MH$io_uring_queue_exit =
        u("io_uring_queue_exit", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle MH$io_uring_get_sqe =
        u("io_uring_get_sqe", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle MH$io_uring_prep_read_fixed =
        u(
            "io_uring_prep_read_fixed",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_INT));
    static final MethodHandle MH$io_uring_sqe_set_data64 =
        u("io_uring_sqe_set_data64", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
    static final MethodHandle MH$io_uring_register_buffers =
        u("io_uring_register_buffers", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle MH$io_uring_submit_and_wait =
        u("io_uring_submit_and_wait", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    static final MethodHandle MH$io_uring_peek_batch_cqe =
        u("io_uring_peek_batch_cqe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle MH$io_uring_cqe_get_data64 =
        u("io_uring_cqe_get_data64", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle MH$io_uring_cq_advance =
        u("io_uring_cq_advance", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
  }

  private static volatile Boolean available;

  /** True iff liburing-ffi loads, symbols resolve, and a probe ring initializes. */
  static boolean isAvailable() {
    Boolean a = available;
    if (a != null) return a;
    synchronized (IoUring.class) {
      if (available != null) return available;
      boolean ok = false;
      try (Arena probe = Arena.ofConfined()) {
        MemorySegment ring = probe.allocate(512);
        if (queueInit(2, ring, 0) == 0) {
          queueExit(ring);
          ok = true;
        }
      } catch (Throwable _) {
        ok = false;
      }
      return available = ok;
    }
  }

  /*
   * Every native call goes through a small wrapper using invokeExact with the handle's precise
   * signature: unlike invokeWithArguments it neither boxes arguments nor type-checks them at run
   * time, which matters because the read path makes several of these calls per vector. A failure
   * here is a binding bug, not a condition callers can handle, hence AssertionError.
   */

  private static int open(MemorySegment path, int flags) {
    try {
      return (int) Native.MH$open.invokeExact(path, flags, 0);
    } catch (Throwable t) {
      throw new AssertionError("open", t);
    }
  }

  private static int libcClose(int fd) {
    try {
      return (int) Native.MH$close.invokeExact(fd);
    } catch (Throwable t) {
      throw new AssertionError("close", t);
    }
  }

  private static int fcntl(int fd, int cmd) {
    try {
      return (int) Native.MH$fcntl.invokeExact(fd, cmd);
    } catch (Throwable t) {
      throw new AssertionError("fcntl", t);
    }
  }

  private static int queueInit(int entries, MemorySegment ring, int flags) {
    try {
      return (int) Native.MH$io_uring_queue_init.invokeExact(entries, ring, flags);
    } catch (Throwable t) {
      throw new AssertionError("io_uring_queue_init", t);
    }
  }

  private static void queueExit(MemorySegment ring) {
    try {
      Native.MH$io_uring_queue_exit.invokeExact(ring);
    } catch (Throwable t) {
      throw new AssertionError("io_uring_queue_exit", t);
    }
  }

  private static MemorySegment getSqe(MemorySegment ring) {
    try {
      return (MemorySegment) Native.MH$io_uring_get_sqe.invokeExact(ring);
    } catch (Throwable t) {
      throw new AssertionError("io_uring_get_sqe", t);
    }
  }

  private static void prepReadFixed(
      MemorySegment sqe, int fd, MemorySegment buf, int len, long offset, int bufIndex) {
    try {
      Native.MH$io_uring_prep_read_fixed.invokeExact(sqe, fd, buf, len, offset, bufIndex);
    } catch (Throwable t) {
      throw new AssertionError("io_uring_prep_read_fixed", t);
    }
  }

  private static void setData64(MemorySegment sqe, long data) {
    try {
      Native.MH$io_uring_sqe_set_data64.invokeExact(sqe, data);
    } catch (Throwable t) {
      throw new AssertionError("io_uring_sqe_set_data64", t);
    }
  }

  private static int registerBuffers(MemorySegment ring, MemorySegment iovecs, int count) {
    try {
      return (int) Native.MH$io_uring_register_buffers.invokeExact(ring, iovecs, count);
    } catch (Throwable t) {
      throw new AssertionError("io_uring_register_buffers", t);
    }
  }

  private static int submitAndWait(MemorySegment ring, int waitNr) {
    try {
      return (int) Native.MH$io_uring_submit_and_wait.invokeExact(ring, waitNr);
    } catch (Throwable t) {
      throw new AssertionError("io_uring_submit_and_wait", t);
    }
  }

  private static int peekBatchCqe(MemorySegment ring, MemorySegment cqes, int count) {
    try {
      return (int) Native.MH$io_uring_peek_batch_cqe.invokeExact(ring, cqes, count);
    } catch (Throwable t) {
      throw new AssertionError("io_uring_peek_batch_cqe", t);
    }
  }

  private static long cqeGetData64(MemorySegment cqe) {
    try {
      return (long) Native.MH$io_uring_cqe_get_data64.invokeExact(cqe);
    } catch (Throwable t) {
      throw new AssertionError("io_uring_cqe_get_data64", t);
    }
  }

  private static void cqAdvance(MemorySegment ring, int count) {
    try {
      Native.MH$io_uring_cq_advance.invokeExact(ring, count);
    } catch (Throwable t) {
      throw new AssertionError("io_uring_cq_advance", t);
    }
  }

  private final int qd;
  private final int maxRings;

  /** Idle rings available to borrow; a ring is only ever driven by one thread at a time. */
  private final ArrayBlockingQueue<Ring> idle;

  private final AtomicInteger created = new AtomicInteger();

  /** O_DIRECT descriptors handed out and not yet closed. Visible for testing. */
  private final AtomicInteger openFds = new AtomicInteger();

  private volatile boolean closed;

  IoUring(int queueDepth, int maxRings) {
    if (!isAvailable()) {
      throw new IllegalStateException("io_uring unavailable");
    }
    if (maxRings < 1) {
      throw new IllegalArgumentException("maxRings must be >= 1, got " + maxRings);
    }
    this.qd = Integer.highestOneBit(Math.max(queueDepth, 2) - 1) << 1;
    this.maxRings = maxRings;
    this.idle = new ArrayBlockingQueue<>(maxRings);
  }

  /**
   * Opens an O_DIRECT read-only fd for {@code path}. The caller owns the returned fd and must
   * {@link #closeFd} it; tying it to the {@link org.apache.lucene.store.IndexInput} that uses it is
   * what lets a merged-away segment release the file instead of pinning it for the directory's
   * lifetime.
   */
  int openDirect(String path) throws IOException {
    try (Arena a = Arena.ofConfined()) {
      int fd = open(a.allocateFrom(path), O_RDONLY | O_DIRECT);
      if (fd < 0) {
        throw new IOException("open with O_DIRECT failed for " + path);
      }
      // open() may succeed while ignoring an O_DIRECT flag it does not recognise, which would leave
      // us doing buffered reads under a name that promises otherwise. Confirm before trusting it.
      int flags = fcntl(fd, F_GETFL);
      if (flags < 0 || (flags & O_DIRECT) == 0) {
        libcClose(fd);
        throw new IOException("O_DIRECT not honoured for " + path);
      }
      openFds.incrementAndGet();
      return fd;
    }
  }

  /** Closes an fd handed out by {@link #openDirect}. */
  void closeFd(int fd) {
    openFds.decrementAndGet();
    libcClose(fd);
  }

  /**
   * Descriptors opened by {@link #openDirect} and not yet closed. Visible for testing, which uses
   * it to check that closing an {@link org.apache.lucene.store.IndexInput} releases its descriptor.
   */
  int openFdCount() {
    return openFds.get();
  }

  /** Block-aligned byte count that must be read to cover {@code len} bytes at {@code pos}. */
  private static int span(long pos, int len) {
    int skew = (int) (pos & (BLK - 1));
    return (skew + len + BLK - 1) & -BLK;
  }

  private final class Ring {
    final Arena arena = Arena.ofShared();
    final MemorySegment ring = arena.allocate(512);
    final MemorySegment buf = arena.allocate((long) qd * BLK, BLK);
    final MemorySegment cqePtrs = arena.allocate((long) qd * ADDRESS.byteSize());

    Ring() {
      int r = queueInit(qd, ring, 0);
      if (r < 0) {
        throw new RuntimeException("io_uring_queue_init=" + r);
      }
      MemorySegment iov = arena.allocate(16);
      iov.set(JAVA_LONG, 0, buf.address());
      iov.set(JAVA_LONG, 8, (long) qd * BLK);
      int rb = registerBuffers(ring, iov, 1);
      if (rb < 0) {
        queueExit(ring);
        throw new RuntimeException("register_buffers=" + rb);
      }
    }

    void free() {
      queueExit(ring);
      arena.close();
    }
  }

  /**
   * Borrows an idle ring, creating one while under {@code maxRings}, otherwise waiting for a peer
   * to finish. Rings are not thread-affine (the registered buffer travels with the ring), so
   * borrowing per call rather than per thread keeps the resource count bounded by read concurrency
   * instead of by how many threads have ever reranked, and leaves nothing behind when a thread
   * dies.
   */
  private Ring acquire() throws IOException {
    Ring r = idle.poll();
    if (r != null) {
      return r;
    }
    if (created.incrementAndGet() <= maxRings) {
      return new Ring();
    }
    created.decrementAndGet();
    try {
      return idle.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted waiting for an io_uring ring", e);
    }
  }

  /**
   * Rings currently allocated, whether idle or on loan. Visible for testing, which uses it to check
   * that a ring is discarded rather than pooled when a read fails.
   */
  int ringCount() {
    return created.get();
  }

  /** Returns a quiesced ring to the pool: every submission it carried has been reaped. */
  private void release(Ring r) {
    if (closed || idle.offer(r) == false) {
      created.decrementAndGet();
      r.free();
    }
  }

  /**
   * Destroys a ring instead of pooling it. Used whenever a read gives up part way through, because
   * submissions that were never reaped would otherwise surface as completions for the <em>next</em>
   * borrower, which would decode them against its own request array and silently return the wrong
   * vectors. Losing one ring on an I/O error is a cheap price for that guarantee.
   */
  private void discard(Ring r) {
    created.decrementAndGet();
    try {
      r.free();
    } catch (Throwable _) {
      // best effort: the ring is being abandoned anyway
    }
  }

  /**
   * Reads {@code count} dim-float vectors, vector {@code i} starting at {@code positions[i]} in
   * {@code fd}, into {@code out} at {@code i * dim}.
   */
  void read(int fd, long[] positions, int dim, int count, float[] out) throws IOException {
    if (closed) {
      throw new IOException("closed");
    }
    if (count == 0) {
      return;
    }
    int len = dim * 4;
    // O_DIRECT requires a block-aligned file offset, length and buffer address, so each vector is
    // fetched as the aligned span that contains it and copied out from its offset within that span.
    // A vector whose position is not block-aligned therefore straddles one extra block, which is
    // why page-aligning the vector data (see Lucene99FlatVectorsWriter) halves the bytes read.
    // Blocks per slot: the vector rounded up, plus one more because an unaligned start pushes it
    // into the following block. Fewer slots per submission than qd when a vector spans several.
    final int spanBlocks = (len + BLK - 1) / BLK + 1;
    final int slots = Math.max(1, qd / spanBlocks);
    Ring r = acquire();
    boolean quiesced = false;
    try {
      for (int base = 0; base < count; base += slots) {
        int m = Math.min(slots, count - base);
        for (int k = 0; k < m; k++) {
          MemorySegment sqe = getSqe(r.ring);
          if (MemorySegment.NULL.equals(sqe)) {
            throw new IOException("io_uring SQ ring exhausted");
          }
          long pos = positions[base + k];
          int span = span(pos, len);
          prepReadFixed(
              sqe,
              fd,
              r.buf.asSlice((long) k * spanBlocks * BLK, span),
              span,
              pos & -(long) BLK,
              0);
          setData64(sqe, base + k);
        }
        int got = 0;
        while (got < m) {
          int sub = submitAndWait(r.ring, m - got);
          if (sub < 0 && sub != -4 /* -EINTR */) {
            throw new IOException("io_uring_submit_and_wait errno=" + (-sub));
          }
          int n = peekBatchCqe(r.ring, r.cqePtrs, qd);
          try {
            for (int c = 0; c < n; c++) {
              MemorySegment cqe = r.cqePtrs.getAtIndex(ADDRESS, c).reinterpret(16);
              int i = (int) cqeGetData64(cqe);
              int res = cqe.get(JAVA_INT, 8);
              long pos = positions[i];
              if (res < 0) {
                throw new IOException("io_uring read errno=" + (-res) + " at " + pos);
              }
              int skew = (int) (pos & (BLK - 1));
              if (res < skew + len) {
                throw new IOException("io_uring short read at " + pos + " res=" + res);
              }
              // JAVA_FLOAT_UNALIGNED: a vector's offset inside its span need not be 4-byte aligned.
              MemorySegment.copy(
                  r.buf,
                  JAVA_FLOAT_UNALIGNED,
                  (long) (i - base) * spanBlocks * BLK + skew,
                  out,
                  i * dim,
                  dim);
            }
          } finally {
            if (n > 0) {
              cqAdvance(r.ring, n);
            }
          }
          got += n;
        }
      }
      quiesced = true; // every submission has been reaped, so the ring is reusable
    } finally {
      if (quiesced) {
        release(r);
      } else {
        discard(r);
      }
    }
  }

  /**
   * Frees every idle ring. Rings still on loan are freed by the borrower on {@link #release}, so a
   * read in flight during close finishes against a valid ring instead of a freed one.
   */
  @Override
  public void close() {
    closed = true;
    for (Ring r; (r = idle.poll()) != null; ) {
      created.decrementAndGet();
      try {
        r.free();
      } catch (Throwable _) {
      }
    }
  }
}
