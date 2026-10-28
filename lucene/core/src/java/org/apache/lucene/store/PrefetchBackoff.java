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
package org.apache.lucene.store;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides whether {@link MemorySegmentIndexInput#prefetch} should check the page cache before
 * calling madvise. One instance is shared by all clones and slices of an input.
 *
 * <p>Probe on every call until {@link #N} consecutive hits. After that, probe again only when the
 * process has taken a major page fault since this file's last successful probe: a hot index takes
 * no major faults, so it never probes, and the first read of an evicted page is itself a major
 * fault, so every file probes on its next call after an eviction starts. A miss goes back to
 * probing on every call.
 */
final class PrefetchBackoff {

  // A probe (mincore) costs about a microsecond. A page cache miss costs tens of microseconds on a
  // fast local NVMe and a few hundred on network storage. After n hits without a miss, the miss
  // rate is very likely below 3/n (the "rule of three"), so it takes on the order of a thousand
  // hits to be confident enough to stop probing.
  static final int N = 1024;

  // How often the process-wide major fault count is read: callers look at the clock once every
  // CHECK_EVERY calls, and getrusage runs at most once per REFRESH_NANOS.
  static final int CHECK_EVERY = 64;
  static final long REFRESH_NANOS = 1_000_000;

  // Bumped whenever the process's major fault count has grown since the last refresh.
  private static final AtomicLong FAULT_EPOCH = new AtomicLong();
  private static final AtomicLong LAST_REFRESH_NANOS = new AtomicLong(System.nanoTime());
  private static volatile long lastMajorFaults = -1;

  private final AtomicInteger consecutiveHits;
  private final AtomicInteger inputs;
  // The fault epoch in which this file's last probe hit. Racy on purpose, like the counter.
  private volatile long confirmedEpoch = -1;

  PrefetchBackoff() {
    this(false);
  }

  /** Every input probes its first call, preloaded or not. */
  PrefetchBackoff(boolean preloaded) {
    consecutiveHits = new AtomicInteger(N - 1);
    inputs = new AtomicInteger();
  }

  /** Initial value for a new input's call counter; spreads the clock checks across clones. */
  int nextSeed() {
    return (inputs.incrementAndGet() * 0x9E3779B9)
        >>> Integer.numberOfLeadingZeros(CHECK_EVERY - 1);
  }

  /** {@code calls} is the input's own call count, starting from {@link #nextSeed()}. */
  boolean shouldProbe(int calls, NativeAccess nativeAccess) {
    if ((calls & (CHECK_EVERY - 1)) == 0) {
      maybeRefreshEpoch(nativeAccess);
    }
    return consecutiveHits.get() < N || confirmedEpoch != FAULT_EPOCH.get();
  }

  // Both updates are racy on purpose. A lost increment or a repeated reset is harmless, and the
  // guards keep the hot path from dirtying a cache line that every core reads.

  void onHit() {
    if (consecutiveHits.get() < N) {
      consecutiveHits.incrementAndGet();
    }
    final long epoch = FAULT_EPOCH.get();
    if (confirmedEpoch != epoch) {
      confirmedEpoch = epoch;
    }
  }

  void onMiss() {
    if (consecutiveHits.get() != 0) {
      consecutiveHits.set(0);
    }
  }

  /** Reads the major fault count at most once per {@link #REFRESH_NANOS}, across all threads. */
  static void maybeRefreshEpoch(NativeAccess nativeAccess) {
    maybeRefreshEpoch(nativeAccess, System.nanoTime());
  }

  // package-private so tests can pass the clock instead of sleeping
  static void maybeRefreshEpoch(NativeAccess nativeAccess, long now) {
    final long last = LAST_REFRESH_NANOS.get();
    if (now - last < REFRESH_NANOS || LAST_REFRESH_NANOS.compareAndSet(last, now) == false) {
      return;
    }
    final long faults = nativeAccess.majorFaults();
    if (faults < 0 || faults != lastMajorFaults) {
      // no counter means no signal: treat every refresh as a possible eviction
      lastMajorFaults = faults;
      FAULT_EPOCH.incrementAndGet();
    }
  }

  /** Simulates a major fault; for tests. */
  static void advanceEpochForTesting() {
    FAULT_EPOCH.incrementAndGet();
  }
}
