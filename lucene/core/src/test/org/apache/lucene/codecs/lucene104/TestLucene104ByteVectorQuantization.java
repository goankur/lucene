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

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnByteVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RescoreTopNQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;
import org.apache.lucene.util.quantization.QuantizedVectorsReader;

/**
 * Byte fields under the 1-bit quantized format get 1-bit codes, computed from the bytes, alongside
 * the raw bytes: graph search runs on the codes and rescoring on the raw bytes.
 */
public class TestLucene104ByteVectorQuantization extends LuceneTestCase {

  private static final String FIELD = "v";

  private static Codec codec() {
    return TestUtil.alwaysKnnVectorsFormat(
        new Lucene104HnswScalarQuantizedVectorsFormat(
            ScalarEncoding.SINGLE_BIT_QUERY_NIBBLE, 16, 100));
  }

  private static byte[] randomByteVector(int dim) {
    byte[] v = new byte[dim];
    for (int i = 0; i < dim; i++) {
      v[i] = (byte) (random().nextInt(255) - 127);
    }
    return v;
  }

  public void testByteFieldIsQuantizedAndRescoresFromRawBytes() throws IOException {
    final int dim = 64;
    final int numDocs = 1000 + random().nextInt(500);
    final byte[][] docs = new byte[numDocs][];
    try (Directory dir = newDirectory()) {
      IndexWriterConfig iwc = new IndexWriterConfig().setCodec(codec());
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          docs[i] = randomByteVector(dim);
          Document doc = new Document();
          doc.add(
              new KnnByteVectorField(
                  FIELD, docs[i], VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT));
          doc.add(new StoredField("id", i));
          w.addDocument(doc);
          if (random().nextInt(300) == 0) {
            w.commit();
          }
        }
        w.forceMerge(1);
      }

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        LeafReader leaf = getOnlyLeafReader(reader);

        // The byte field holds codes alongside its raw bytes.
        KnnVectorsReader vectorsReader =
            ((CodecReader) leaf).getVectorReader().unwrapReaderForField(FIELD);
        var codes = ((QuantizedVectorsReader) vectorsReader).getQuantizedVectorValues(FIELD);
        assertNotNull("byte field must be quantized", codes);
        assertEquals(numDocs, codes.size());

        // vectorValue() returns the raw bytes unchanged.
        ByteVectorValues values = leaf.getByteVectorValues(FIELD);
        assertTrue(
            values
                instanceof Lucene104ScalarQuantizedVectorsReader.ScalarQuantizedByteVectorValues);
        IndexSearcher searcher = newSearcher(reader);
        var stored = reader.storedFields();
        var it = values.iterator();
        for (int doc = it.nextDoc();
            doc != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
            doc = it.nextDoc()) {
          int id = stored.document(doc).getField("id").numericValue().intValue();
          assertArrayEquals(docs[id], values.vectorValue(it.index()));
        }

        // Graph search on codes + rescoring on raw bytes finds most of the exact top 10.
        final int k = 10;
        double recall = 0;
        final int numQueries = 20;
        for (int q = 0; q < numQueries; q++) {
          byte[] query = randomByteVector(dim);
          Query knn = new KnnByteVectorQuery(FIELD, query, k * 10);
          Query rescored = RescoreTopNQuery.createFullPrecisionRescorerQuery(knn, query, FIELD, k);
          TopDocs top = searcher.search(rescored, k);
          Set<Integer> found = new HashSet<>();
          for (ScoreDoc sd : top.scoreDocs) {
            found.add(stored.document(sd.doc).getField("id").numericValue().intValue());
          }
          recall += overlapWithExact(docs, query, k, found) / (double) k;
        }
        recall /= numQueries;
        assertTrue("recall@10 was " + recall, recall > 0.8);
      }
    }
  }

  private static int overlapWithExact(byte[][] docs, byte[] query, int k, Set<Integer> found) {
    Integer[] ids = new Integer[docs.length];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = i;
    }
    java.util.Arrays.sort(
        ids,
        (a, b) ->
            Integer.compare(
                VectorUtil.dotProduct(docs[b], query), VectorUtil.dotProduct(docs[a], query)));
    int hits = 0;
    for (int i = 0; i < k; i++) {
      if (found.contains(ids[i])) {
        hits++;
      }
    }
    return hits;
  }
}
