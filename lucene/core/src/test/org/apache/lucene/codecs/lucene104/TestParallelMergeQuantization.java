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
package org.apache.lucene.codecs.lucene104;

import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.quantization.OptimizedScalarQuantizer;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;

/** The parallel merge quantization writes exactly the records of the serial path. */
public class TestParallelMergeQuantization extends LuceneTestCase {

  public void testSameBytesAsSerial() throws Exception {
    int dim = 4 + random().nextInt(300);
    // more than one block, and a partial last block
    int count =
        Lucene104ScalarQuantizedVectorsWriter.MERGE_QUANTIZE_BLOCK + 1 + random().nextInt(5000);
    List<float[]> vectors = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      float[] v = new float[dim];
      for (int d = 0; d < dim; d++) {
        v[d] = random().nextInt(255) - 127; // byte values widened to float, as byte fields are
      }
      vectors.add(v);
    }
    float[] centroid = new float[dim];
    for (float[] v : vectors) {
      for (int d = 0; d < dim; d++) {
        centroid[d] += v[d] / count;
      }
    }
    VectorSimilarityFunction sim = VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT;
    for (ScalarEncoding encoding : ScalarEncoding.values()) {
      try (Directory dir = newDirectory()) {
        try (IndexOutput serial = dir.createOutput("serial", IOContext.DEFAULT);
            IndexOutput parallel = dir.createOutput("parallel", IOContext.DEFAULT)) {
          Lucene104ScalarQuantizedVectorsWriter.writeVectorData(
              serial,
              new Lucene104ScalarQuantizedVectorsWriter.QuantizedFloatVectorValues(
                  FloatVectorValues.fromFloats(copy(vectors), dim),
                  new OptimizedScalarQuantizer(sim),
                  encoding,
                  centroid));
          Lucene104ScalarQuantizedVectorsWriter.writeVectorDataParallel(
              parallel,
              FloatVectorValues.fromFloats(copy(vectors), dim),
              new OptimizedScalarQuantizer(sim),
              encoding,
              centroid,
              1 + random().nextInt(8));
        }
        assertEquals(encoding.toString(), dir.fileLength("serial"), dir.fileLength("parallel"));
        try (IndexInput a = dir.openInput("serial", IOContext.DEFAULT);
            IndexInput b = dir.openInput("parallel", IOContext.DEFAULT)) {
          byte[] x = new byte[(int) a.length()];
          byte[] y = new byte[(int) b.length()];
          a.readBytes(x, 0, x.length);
          b.readBytes(y, 0, y.length);
          assertArrayEquals(encoding.toString(), x, y);
        }
      }
    }
  }

  private static List<float[]> copy(List<float[]> in) {
    List<float[]> out = new ArrayList<>(in.size());
    for (float[] v : in) {
      out.add(v.clone());
    }
    return out;
  }
}
