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
package org.apache.lucene.internal.vectorization;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FilterIndexInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.MemorySegmentAccessInput;
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.hnsw.RandomVectorScorerSupplier;

/** A score supplier of vectors whose element size is byte. */
public abstract sealed class Lucene99MemorySegmentByteVectorScorerSupplier
    implements RandomVectorScorerSupplier {
  final int vectorByteSize;
  final int maxOrd;
  final MemorySegmentAccessInput input;
  final KnnVectorValues values; // to support ordToDoc/getAcceptOrds
  byte[] scratch1, scratch2;
  MemorySegment[] offHeapScratch;
  private static Arena offHeap;

  private static final int FIRST_OFFHEAP_SCRATCH = 0;
  private static final int SECOND_OFFHEAP_SCRATCH = 1;

  /**
   * Return an optional whose value, if present, is the scorer supplier. Otherwise, an empty
   * optional is returned.
   */
  static Optional<RandomVectorScorerSupplier> create(
      VectorSimilarityFunction type, IndexInput input, KnnVectorValues values) {
    assert values instanceof ByteVectorValues;
    input = FilterIndexInput.unwrapOnlyTest(input);
    if (!(input instanceof MemorySegmentAccessInput msInput)) {
      return Optional.empty();
    }
    checkInvariants(values.size(), values.getVectorByteLength(), input);
    return switch (type) {
      case COSINE -> Optional.of(new CosineSupplier(msInput, values));
      case DOT_PRODUCT ->
          Constants.NATIVE_DOT_PRODUCT_ENABLED == false
              ? Optional.of(new DotProductSupplier(msInput, values))
              : Optional.of(new NativeDotProductSupplier(msInput, values));
      case EUCLIDEAN -> Optional.of(new EuclideanSupplier(msInput, values));
      case MAXIMUM_INNER_PRODUCT -> Optional.of(new MaxInnerProductSupplier(msInput, values));
    };
  }

  Lucene99MemorySegmentByteVectorScorerSupplier(
      MemorySegmentAccessInput input, KnnVectorValues values) {
    this.input = input;
    this.values = values;
    this.vectorByteSize = values.getVectorByteLength();
    this.maxOrd = values.size();
  }

  static void checkInvariants(int maxOrd, int vectorByteLength, IndexInput input) {
    if (input.length() < (long) vectorByteLength * maxOrd) {
      throw new IllegalArgumentException("input length is less than expected vector data");
    }
  }

  final void checkOrdinal(int ord) {
    if (ord < 0 || ord >= maxOrd) {
      throw new IllegalArgumentException("illegal ordinal: " + ord);
    }
  }

  final MemorySegment getNativeSegment(int ord, int sid) throws IOException {
    long byteOffset = (long) ord * vectorByteSize;
    MemorySegment seg = input.segmentSliceOrNull(byteOffset, vectorByteSize);
    if (seg == null) {
      if (offHeapScratch[sid]
          == null) { // Should be rare, this means current vector was split across memory segments
        offHeapScratch[sid] =
            offHeap.allocate(vectorByteSize, ValueLayout.JAVA_BYTE.byteAlignment());
      }
      input.readBytes(byteOffset, offHeapScratch[sid], 0, vectorByteSize);
      seg = offHeapScratch[sid];
    }
    return seg;
  }

  final MemorySegment getFirstSegment(int ord) throws IOException {
    long byteOffset = (long) ord * vectorByteSize;
    MemorySegment seg = input.segmentSliceOrNull(byteOffset, vectorByteSize);
    if (seg == null) {
      if (scratch1 == null) {
        scratch1 = new byte[vectorByteSize];
      }
      input.readBytes(byteOffset, scratch1, 0, vectorByteSize);
      seg = MemorySegment.ofArray(scratch1);
    }
    return seg;
  }

  final MemorySegment getSecondSegment(int ord) throws IOException {
    long byteOffset = (long) ord * vectorByteSize;
    MemorySegment seg = input.segmentSliceOrNull(byteOffset, vectorByteSize);
    if (seg == null) {
      if (scratch2 == null) {
        scratch2 = new byte[vectorByteSize];
      }
      input.readBytes(byteOffset, scratch2, 0, vectorByteSize);
      seg = MemorySegment.ofArray(scratch2);
    }
    return seg;
  }

  static final class CosineSupplier extends Lucene99MemorySegmentByteVectorScorerSupplier {

    CosineSupplier(MemorySegmentAccessInput input, KnnVectorValues values) {
      super(input, values);
    }

    @Override
    public RandomVectorScorer scorer(int ord) {
      checkOrdinal(ord);
      return new RandomVectorScorer.AbstractRandomVectorScorer(values) {
        @Override
        public float score(int node) throws IOException {
          checkOrdinal(node);
          float raw = PanamaVectorUtilSupport.cosine(getFirstSegment(ord), getSecondSegment(node));
          return (1 + raw) / 2;
        }
      };
    }

    @Override
    public CosineSupplier copy() throws IOException {
      return new CosineSupplier(input.clone(), values);
    }
  }

  static final class NativeDotProductSupplier
      extends Lucene99MemorySegmentByteVectorScorerSupplier {

    NativeDotProductSupplier(MemorySegmentAccessInput input, KnnVectorValues values) {
      super(input, values);
      offHeap = Arena.ofAuto();
      offHeapScratch = new MemorySegment[2];
    }

    @Override
    public RandomVectorScorer scorer(int ord) {
      checkOrdinal(ord);
      return new RandomVectorScorer.AbstractRandomVectorScorer(values) {
        @Override
        public float score(int node) throws IOException {
          checkOrdinal(node);
          // divide by 2 * 2^14 (maximum absolute value of product of 2 signed bytes) * len
          int raw =
              PanamaVectorUtilSupport.nativeDotProduct(
                  getNativeSegment(ord, FIRST_OFFHEAP_SCRATCH),
                  getNativeSegment(node, SECOND_OFFHEAP_SCRATCH));
          return 0.5f + raw / (float) (values.dimension() * (1 << 15));
        }
      };
    }

    @Override
    public NativeDotProductSupplier copy() throws IOException {
      return new NativeDotProductSupplier(input.clone(), values);
    }
  }

  static final class DotProductSupplier extends Lucene99MemorySegmentByteVectorScorerSupplier {

    DotProductSupplier(MemorySegmentAccessInput input, KnnVectorValues values) {
      super(input, values);
    }

    @Override
    public RandomVectorScorer scorer(int ord) {
      checkOrdinal(ord);
      return new RandomVectorScorer.AbstractRandomVectorScorer(values) {
        @Override
        public float score(int node) throws IOException {
          checkOrdinal(node);
          // divide by 2 * 2^14 (maximum absolute value of product of 2 signed bytes) * len
          float raw =
              PanamaVectorUtilSupport.dotProduct(getFirstSegment(ord), getSecondSegment(node));
          return 0.5f + raw / (float) (values.dimension() * (1 << 15));
        }
      };
    }

    @Override
    public DotProductSupplier copy() throws IOException {
      return new DotProductSupplier(input.clone(), values);
    }
  }

  static final class EuclideanSupplier extends Lucene99MemorySegmentByteVectorScorerSupplier {

    EuclideanSupplier(MemorySegmentAccessInput input, KnnVectorValues values) {
      super(input, values);
    }

    @Override
    public RandomVectorScorer scorer(int ord) {
      checkOrdinal(ord);
      return new RandomVectorScorer.AbstractRandomVectorScorer(values) {
        @Override
        public float score(int node) throws IOException {
          checkOrdinal(node);
          float raw =
              PanamaVectorUtilSupport.squareDistance(getFirstSegment(ord), getSecondSegment(node));
          return 1 / (1f + raw);
        }
      };
    }

    @Override
    public EuclideanSupplier copy() throws IOException {
      return new EuclideanSupplier(input.clone(), values);
    }
  }

  static final class MaxInnerProductSupplier extends Lucene99MemorySegmentByteVectorScorerSupplier {

    MaxInnerProductSupplier(MemorySegmentAccessInput input, KnnVectorValues values) {
      super(input, values);
    }

    @Override
    public RandomVectorScorer scorer(int ord) {
      checkOrdinal(ord);
      return new RandomVectorScorer.AbstractRandomVectorScorer(values) {
        @Override
        public float score(int node) throws IOException {
          checkOrdinal(node);
          float raw =
              PanamaVectorUtilSupport.dotProduct(getFirstSegment(ord), getSecondSegment(node));
          if (raw < 0) {
            return 1 / (1 + -1 * raw);
          }
          return raw + 1;
        }
      };
    }

    @Override
    public MaxInnerProductSupplier copy() throws IOException {
      return new MaxInnerProductSupplier(input.clone(), values);
    }
  }
}
