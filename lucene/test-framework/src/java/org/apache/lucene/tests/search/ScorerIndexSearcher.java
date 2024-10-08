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
package org.apache.lucene.tests.search;

import java.io.IOException;
import java.util.concurrent.Executor;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;

/** An {@link IndexSearcher} that always uses the {@link Scorer} API, never {@link BulkScorer}. */
public class ScorerIndexSearcher extends IndexSearcher {

  /**
   * Creates a searcher searching the provided index. Search on individual segments will be run in
   * the provided {@link Executor}.
   *
   * @see IndexSearcher#IndexSearcher(IndexReader, Executor)
   */
  public ScorerIndexSearcher(IndexReader r, Executor executor) {
    super(r, executor);
  }

  /**
   * Creates a searcher searching the provided index.
   *
   * @see IndexSearcher#IndexSearcher(IndexReader)
   */
  public ScorerIndexSearcher(IndexReader r) {
    super(r);
  }

  @Override
  protected void searchLeaf(
      LeafReaderContext ctx, int minDocId, int maxDocId, Weight weight, Collector collector)
      throws IOException {
    // the default slices method does not create segment partitions, and we don't provide an
    // executor to this searcher in our codebase, so we should not run into this problem. This class
    // can though be used externally, hence it is better to provide a clear and hard error.
    if (minDocId != 0 || maxDocId != DocIdSetIterator.NO_MORE_DOCS) {
      throw new IllegalStateException(
          "intra-segment concurrency is not supported by this searcher");
    }
    // we force the use of Scorer (not BulkScorer) to make sure
    // that the scorer passed to LeafCollector.setScorer supports
    // Scorer.getChildren
    Scorer scorer = weight.scorer(ctx);
    if (scorer != null) {
      final DocIdSetIterator iterator = scorer.iterator();
      final LeafCollector leafCollector = collector.getLeafCollector(ctx);
      leafCollector.setScorer(scorer);
      final Bits liveDocs = ctx.reader().getLiveDocs();
      for (int doc = iterator.nextDoc();
          doc != DocIdSetIterator.NO_MORE_DOCS;
          doc = iterator.nextDoc()) {
        if (liveDocs == null || liveDocs.get(doc)) {
          leafCollector.collect(doc);
        }
      }
    }
  }
}
