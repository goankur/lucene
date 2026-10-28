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

import static org.apache.lucene.store.PrefetchBackoff.CHECK_EVERY;
import static org.apache.lucene.store.PrefetchBackoff.N;

import java.lang.foreign.MemorySegment;
import java.util.Optional;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.Constants;

public class TestPrefetchBackoff extends LuceneTestCase {

  /** A NativeAccess whose major fault count the test controls. */
  private static final class FakeFaults extends NativeAccess {
    long faults;

    @Override
    public void madvise(MemorySegment segment, ReadAdvice readAdvice) {}

    @Override
    public void madviseWillNeed(MemorySegment segment) {}

    @Override
    public int getPageSize() {
      return 4096;
    }

    @Override
    public long majorFaults() {
      return faults;
    }
  }

  private final FakeFaults faults = new FakeFaults();

  // calls that never trigger a refresh of the fault epoch
  private static int noCheck(int i) {
    return i * CHECK_EVERY + 1;
  }

  public void testFirstCallProbesThenStopsOnHit() {
    PrefetchBackoff backoff = new PrefetchBackoff();
    assertTrue(backoff.shouldProbe(noCheck(0), faults));
    backoff.onHit();
    for (int i = 1; i < 10_000; i++) {
      assertFalse("call " + i, backoff.shouldProbe(noCheck(i), faults));
    }
  }

  public void testNewFaultEpochProbesOnceThenStops() {
    PrefetchBackoff backoff = new PrefetchBackoff();
    assertTrue(backoff.shouldProbe(noCheck(0), faults));
    backoff.onHit();
    PrefetchBackoff.advanceEpochForTesting();
    assertTrue(backoff.shouldProbe(noCheck(1), faults));
    assertTrue("still unconfirmed until a probe hits", backoff.shouldProbe(noCheck(2), faults));
    backoff.onHit();
    assertFalse(backoff.shouldProbe(noCheck(3), faults));
  }

  public void testMissProbesUnconditionallyUntilNHits() {
    PrefetchBackoff backoff = new PrefetchBackoff();
    backoff.onMiss();
    for (int i = 0; i < N; i++) {
      assertTrue("call " + i, backoff.shouldProbe(noCheck(i), faults));
      backoff.onHit();
    }
    assertFalse(backoff.shouldProbe(noCheck(N), faults));
  }

  public void testMajorFaultsAdvanceTheEpoch() {
    PrefetchBackoff backoff = new PrefetchBackoff();
    assertTrue(backoff.shouldProbe(noCheck(0), faults));
    backoff.onHit();
    // pass the clock explicitly, far enough apart that each call refreshes
    long now = System.nanoTime() + 1_000 * PrefetchBackoff.REFRESH_NANOS;
    faults.faults += 1;
    PrefetchBackoff.maybeRefreshEpoch(faults, now);
    assertTrue(backoff.shouldProbe(noCheck(1), faults));
    backoff.onHit();
    // no new faults: the next refresh leaves the epoch alone
    PrefetchBackoff.maybeRefreshEpoch(faults, now + 2 * PrefetchBackoff.REFRESH_NANOS);
    assertFalse(backoff.shouldProbe(noCheck(2), faults));
    // too soon after the last refresh: a new fault is not looked at yet
    faults.faults += 1;
    PrefetchBackoff.maybeRefreshEpoch(faults, now + 2 * PrefetchBackoff.REFRESH_NANOS + 1);
    assertFalse(backoff.shouldProbe(noCheck(3), faults));
  }

  public void testCountersStartSpreadAcrossClones() {
    PrefetchBackoff backoff = new PrefetchBackoff();
    boolean[] seen = new boolean[CHECK_EVERY];
    for (int i = 0; i < 4 * CHECK_EVERY; i++) {
      seen[backoff.nextSeed() & (CHECK_EVERY - 1)] = true;
    }
    int distinct = 0;
    for (boolean b : seen) {
      distinct += b ? 1 : 0;
    }
    assertTrue("distinct starting offsets: " + distinct, distinct > CHECK_EVERY / 2);
  }

  public void testRealMajorFaultCounter() {
    assumeTrue(
        "getrusage layout is verified for Linux and macOS", Constants.LINUX || Constants.MAC_OS_X);
    Optional<NativeAccess> nativeAccess = NativeAccess.getImplementation();
    assumeTrue("native access is not enabled", nativeAccess.isPresent());
    long before = nativeAccess.get().majorFaults();
    assertTrue("majorFaults=" + before, before >= 0);
    assertTrue(nativeAccess.get().majorFaults() >= before);
  }
}
