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

import static org.apache.lucene.store.PrefetchBackoff.N;
import static org.apache.lucene.store.PrefetchBackoff.SKIP;

import org.apache.lucene.tests.util.LuceneTestCase;

public class TestPrefetchBackoff extends LuceneTestCase {

  private static final int WINDOW = 1 << 20;

  private static void hit(PrefetchBackoff backoff, int times) {
    for (int i = 0; i < times; i++) {
      backoff.onHit();
    }
  }

  private static double probeRate(PrefetchBackoff backoff) {
    int probes = 0;
    for (int i = 0; i < WINDOW; i++) {
      if (backoff.shouldProbe()) {
        probes++;
      }
    }
    return (double) probes / WINDOW;
  }

  public void testStartsConfident() {
    double expected = 1.0 / SKIP;
    assertEquals(expected, probeRate(new PrefetchBackoff()), expected * 0.05);
  }

  public void testProbesUnconditionallyAfterMiss() {
    PrefetchBackoff backoff = new PrefetchBackoff();
    backoff.onMiss();
    for (int i = 0; i < N; i++) {
      assertTrue("call " + i, backoff.shouldProbe());
      backoff.onHit();
    }
  }

  public void testSamplesAfterN() {
    PrefetchBackoff backoff = new PrefetchBackoff();
    backoff.onMiss();
    hit(backoff, 100 * N);
    double expected = 1.0 / SKIP;
    assertEquals(expected, probeRate(backoff), expected * 0.05);
  }

  public void testMissResets() {
    PrefetchBackoff backoff = new PrefetchBackoff();
    backoff.onMiss();
    hit(backoff, N);
    assertTrue(probeRate(backoff) < 1.0);
    backoff.onMiss();
    for (int i = 0; i < N; i++) {
      assertTrue("call " + i, backoff.shouldProbe());
      backoff.onHit();
    }
  }
}
