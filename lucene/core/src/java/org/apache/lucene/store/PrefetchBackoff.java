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

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides whether {@link MemorySegmentIndexInput#prefetch} should check the page cache before
 * calling madvise. One instance is shared by all clones and slices of an input.
 *
 * <p>A new input starts out trusting the page cache and probes a random one in {@link #SKIP} calls.
 * A miss sends us to probing every call until we have seen {@link #N} consecutive hits again.
 * Starting confident spares freshly opened files that are already cached from paying N probes each,
 * while a file that turns out to be cold is caught within about SKIP calls.
 */
final class PrefetchBackoff {

  // A probe (mincore) costs about a microsecond, a page cache miss tens to hundreds. After n hits
  // without a miss the miss rate is very likely below 3/n (the "rule of three"), so after ~1000
  // hits it is below the cost ratio of the two and skipping probes pays off.
  static final int N = 1024;

  // Probing one call in SKIP saves 1 - 1/SKIP of the probe cost while the file stays hot, and
  // bounds the reads we may fail to prefetch after an eviction to about SKIP.
  static final int SKIP = 64;

  private final AtomicInteger consecutiveHits = new AtomicInteger(N);

  /** Samples at random, so probes don't line up with callers' loops or with fresh clones. */
  boolean shouldProbe() {
    return consecutiveHits.get() < N || (ThreadLocalRandom.current().nextInt() & (SKIP - 1)) == 0;
  }

  // Both updates are racy on purpose. A lost increment or a repeated reset is harmless, and the
  // guards keep the hot path from dirtying a cache line that every core reads.

  void onHit() {
    if (consecutiveHits.get() < N) {
      consecutiveHits.incrementAndGet();
    }
  }

  void onMiss() {
    if (consecutiveHits.get() != 0) {
      consecutiveHits.set(0);
    }
  }
}
