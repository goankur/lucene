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
package org.apache.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.search.QueryUtils;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.NumericUtils;

public class TestDocValuesQueries extends LuceneTestCase {

  private Codec getCodec() {
    // small interval size to test with many intervals
    return TestUtil.alwaysDocValuesFormat(new Lucene90DocValuesFormat(random().nextInt(4, 16)));
  }

  public void testDuelPointRangeSortedNumericRangeQuery() throws IOException {
    doTestDuelPointRangeNumericRangeQuery(true, 1, false);
  }

  public void testDuelPointRangeSortedNumericRangeWithSlipperQuery() throws IOException {
    doTestDuelPointRangeNumericRangeQuery(true, 1, true);
  }

  public void testDuelPointRangeMultivaluedSortedNumericRangeQuery() throws IOException {
    doTestDuelPointRangeNumericRangeQuery(true, 3, false);
  }

  public void testDuelPointRangeMultivaluedSortedNumericRangeWithSkipperQuery() throws IOException {
    doTestDuelPointRangeNumericRangeQuery(true, 3, true);
  }

  public void testDuelPointRangeNumericRangeQuery() throws IOException {
    doTestDuelPointRangeNumericRangeQuery(false, 1, false);
  }

  public void testDuelPointRangeNumericRangeWithSkipperQuery() throws IOException {
    doTestDuelPointRangeNumericRangeQuery(false, 1, true);
  }

  public void testDuelPointNumericSortedWithSkipperRangeQuery() throws IOException {
    Directory dir = newDirectory();
    IndexWriterConfig config = new IndexWriterConfig().setCodec(getCodec());
    config.setIndexSort(new Sort(new SortField("dv", SortField.Type.LONG, random().nextBoolean())));
    RandomIndexWriter iw = new RandomIndexWriter(random(), dir, config);
    final int numDocs = atLeast(1000);
    for (int i = 0; i < numDocs; ++i) {
      Document doc = new Document();
      final long value = TestUtil.nextLong(random(), -100, 10000);
      doc.add(NumericDocValuesField.indexedField("dv", value));
      doc.add(new LongPoint("idx", value));
      iw.addDocument(doc);
    }

    final IndexReader reader = iw.getReader();
    final IndexSearcher searcher = newSearcher(reader, false);
    iw.close();

    for (int i = 0; i < 100; ++i) {
      final long min =
          random().nextBoolean() ? Long.MIN_VALUE : TestUtil.nextLong(random(), -100, 10000);
      final long max =
          random().nextBoolean() ? Long.MAX_VALUE : TestUtil.nextLong(random(), -100, 10000);
      final Query q1 = LongPoint.newRangeQuery("idx", min, max);
      final Query q2 = NumericDocValuesField.newSlowRangeQuery("dv", min, max);
      assertSameMatches(searcher, q1, q2, false);
    }
    reader.close();
    dir.close();
  }

  private void doTestDuelPointRangeNumericRangeQuery(
      boolean sortedNumeric, int maxValuesPerDoc, boolean skypper) throws IOException {
    final int iters = atLeast(10);
    for (int iter = 0; iter < iters; ++iter) {
      Directory dir = newDirectory();
      RandomIndexWriter iw;
      if (sortedNumeric || random().nextBoolean()) {
        iw = new RandomIndexWriter(random(), dir);
      } else {
        IndexWriterConfig config = new IndexWriterConfig().setCodec(getCodec());
        config.setIndexSort(
            new Sort(new SortField("dv", SortField.Type.LONG, random().nextBoolean())));
        iw = new RandomIndexWriter(random(), dir, config);
      }
      final int numDocs = atLeast(100);
      for (int i = 0; i < numDocs; ++i) {
        Document doc = new Document();
        final int numValues = TestUtil.nextInt(random(), 0, maxValuesPerDoc);
        for (int j = 0; j < numValues; ++j) {
          final long value = TestUtil.nextLong(random(), -100, 10000);
          if (sortedNumeric) {
            if (skypper) {
              doc.add(SortedNumericDocValuesField.indexedField("dv", value));
            } else {
              doc.add(new SortedNumericDocValuesField("dv", value));
            }
          } else {
            if (skypper) {
              doc.add(NumericDocValuesField.indexedField("dv", value));
            } else {
              doc.add(new NumericDocValuesField("dv", value));
            }
          }
          doc.add(new LongPoint("idx", value));
        }
        iw.addDocument(doc);
      }
      if (random().nextBoolean()) {
        iw.deleteDocuments(LongPoint.newRangeQuery("idx", 0L, 10L));
      }
      final IndexReader reader = iw.getReader();
      final IndexSearcher searcher = newSearcher(reader, false);
      iw.close();

      for (int i = 0; i < 100; ++i) {
        final long min =
            random().nextBoolean() ? Long.MIN_VALUE : TestUtil.nextLong(random(), -100, 10000);
        final long max =
            random().nextBoolean() ? Long.MAX_VALUE : TestUtil.nextLong(random(), -100, 10000);
        final Query q1 = LongPoint.newRangeQuery("idx", min, max);
        final Query q2;
        if (sortedNumeric) {
          q2 = SortedNumericDocValuesField.newSlowRangeQuery("dv", min, max);
        } else {
          q2 = NumericDocValuesField.newSlowRangeQuery("dv", min, max);
        }
        assertSameMatches(searcher, q1, q2, false);
      }

      reader.close();
      dir.close();
    }
  }

  private void doTestDuelPointRangeSortedRangeQuery(
      boolean sortedSet, int maxValuesPerDoc, boolean skypper) throws IOException {
    final int iters = atLeast(10);
    for (int iter = 0; iter < iters; ++iter) {
      Directory dir = newDirectory();
      RandomIndexWriter iw;
      if (sortedSet || random().nextBoolean()) {
        iw = new RandomIndexWriter(random(), dir);
      } else {
        IndexWriterConfig config = new IndexWriterConfig().setCodec(getCodec());
        config.setIndexSort(
            new Sort(new SortField("dv", SortField.Type.STRING, random().nextBoolean())));
        iw = new RandomIndexWriter(random(), dir, config);
      }
      final int numDocs = atLeast(100);
      for (int i = 0; i < numDocs; ++i) {
        Document doc = new Document();
        final int numValues = TestUtil.nextInt(random(), 0, maxValuesPerDoc);
        for (int j = 0; j < numValues; ++j) {
          final long value = TestUtil.nextLong(random(), -100, 10000);
          byte[] encoded = new byte[Long.BYTES];
          LongPoint.encodeDimension(value, encoded, 0);
          if (sortedSet) {
            if (skypper) {
              doc.add(SortedSetDocValuesField.indexedField("dv", newBytesRef(encoded)));
            } else {
              doc.add(new SortedSetDocValuesField("dv", newBytesRef(encoded)));
            }
          } else {
            if (skypper) {
              doc.add(SortedDocValuesField.indexedField("dv", newBytesRef(encoded)));
            } else {
              doc.add(new SortedDocValuesField("dv", newBytesRef(encoded)));
            }
          }
          doc.add(new LongPoint("idx", value));
        }
        iw.addDocument(doc);
      }
      if (random().nextBoolean()) {
        iw.deleteDocuments(LongPoint.newRangeQuery("idx", 0L, 10L));
      }
      final IndexReader reader = iw.getReader();
      final IndexSearcher searcher = newSearcher(reader, false);
      iw.close();

      for (int i = 0; i < 100; ++i) {
        long min =
            random().nextBoolean() ? Long.MIN_VALUE : TestUtil.nextLong(random(), -100, 10000);
        long max =
            random().nextBoolean() ? Long.MAX_VALUE : TestUtil.nextLong(random(), -100, 10000);
        byte[] encodedMin = new byte[Long.BYTES];
        byte[] encodedMax = new byte[Long.BYTES];
        LongPoint.encodeDimension(min, encodedMin, 0);
        LongPoint.encodeDimension(max, encodedMax, 0);
        boolean includeMin = true;
        boolean includeMax = true;
        if (random().nextBoolean()) {
          includeMin = false;
          min++;
        }
        if (random().nextBoolean()) {
          includeMax = false;
          max--;
        }
        final Query q1 = LongPoint.newRangeQuery("idx", min, max);
        final Query q2;
        if (sortedSet) {
          q2 =
              SortedSetDocValuesField.newSlowRangeQuery(
                  "dv",
                  min == Long.MIN_VALUE && random().nextBoolean() ? null : newBytesRef(encodedMin),
                  max == Long.MAX_VALUE && random().nextBoolean() ? null : newBytesRef(encodedMax),
                  includeMin,
                  includeMax);
        } else {
          q2 =
              SortedDocValuesField.newSlowRangeQuery(
                  "dv",
                  min == Long.MIN_VALUE && random().nextBoolean() ? null : newBytesRef(encodedMin),
                  max == Long.MAX_VALUE && random().nextBoolean() ? null : newBytesRef(encodedMax),
                  includeMin,
                  includeMax);
        }
        assertSameMatches(searcher, q1, q2, false);
      }

      reader.close();
      dir.close();
    }
  }

  public void testDuelPointRangeSortedSetRangeQuery() throws IOException {
    doTestDuelPointRangeSortedRangeQuery(true, 1, false);
  }

  public void testDuelPointRangeSortedSetRangeSkipperQuery() throws IOException {
    doTestDuelPointRangeSortedRangeQuery(true, 1, true);
  }

  public void testDuelPointRangeMultivaluedSortedSetRangeQuery() throws IOException {
    doTestDuelPointRangeSortedRangeQuery(true, 3, false);
  }

  public void testDuelPointRangeMultivaluedSortedSetRangeSkipperQuery() throws IOException {
    doTestDuelPointRangeSortedRangeQuery(true, 3, true);
  }

  public void testDuelPointRangeSortedRangeQuery() throws IOException {
    doTestDuelPointRangeSortedRangeQuery(false, 1, false);
  }

  public void testDuelPointRangeSortedRangeSkipperQuery() throws IOException {
    doTestDuelPointRangeSortedRangeQuery(false, 1, true);
  }

  public void testDuelPointSortedSetSortedWithSkipperRangeQuery() throws IOException {
    Directory dir = newDirectory();
    IndexWriterConfig config = new IndexWriterConfig().setCodec(getCodec());
    config.setIndexSort(
        new Sort(new SortField("dv", SortField.Type.STRING, random().nextBoolean())));
    RandomIndexWriter iw = new RandomIndexWriter(random(), dir, config);
    final int numDocs = atLeast(1000);
    for (int i = 0; i < numDocs; ++i) {
      Document doc = new Document();
      final long value = TestUtil.nextLong(random(), -100, 10000);
      byte[] encoded = new byte[Long.BYTES];
      LongPoint.encodeDimension(value, encoded, 0);
      doc.add(SortedDocValuesField.indexedField("dv", newBytesRef(encoded)));
      doc.add(new LongPoint("idx", value));
      iw.addDocument(doc);
    }

    final IndexReader reader = iw.getReader();
    final IndexSearcher searcher = newSearcher(reader, false);
    iw.close();

    for (int i = 0; i < 100; ++i) {
      long min = random().nextBoolean() ? Long.MIN_VALUE : TestUtil.nextLong(random(), -100, 10000);
      long max = random().nextBoolean() ? Long.MAX_VALUE : TestUtil.nextLong(random(), -100, 10000);
      byte[] encodedMin = new byte[Long.BYTES];
      byte[] encodedMax = new byte[Long.BYTES];
      LongPoint.encodeDimension(min, encodedMin, 0);
      LongPoint.encodeDimension(max, encodedMax, 0);
      boolean includeMin = true;
      boolean includeMax = true;
      if (random().nextBoolean()) {
        includeMin = false;
        min++;
      }
      if (random().nextBoolean()) {
        includeMax = false;
        max--;
      }
      final Query q1 = LongPoint.newRangeQuery("idx", min, max);
      final Query q2 =
          SortedDocValuesField.newSlowRangeQuery(
              "dv",
              min == Long.MIN_VALUE && random().nextBoolean() ? null : newBytesRef(encodedMin),
              max == Long.MAX_VALUE && random().nextBoolean() ? null : newBytesRef(encodedMax),
              includeMin,
              includeMax);
      assertSameMatches(searcher, q1, q2, false);
    }
    reader.close();
    dir.close();
  }

  private void assertSameMatches(IndexSearcher searcher, Query q1, Query q2, boolean scores)
      throws IOException {
    final int maxDoc = searcher.getIndexReader().maxDoc();
    final TopDocs td1 = searcher.search(q1, maxDoc, scores ? Sort.RELEVANCE : Sort.INDEXORDER);
    final TopDocs td2 = searcher.search(q2, maxDoc, scores ? Sort.RELEVANCE : Sort.INDEXORDER);
    assertEquals(td1.totalHits.value(), td2.totalHits.value());
    for (int i = 0; i < td1.scoreDocs.length; ++i) {
      assertEquals(td1.scoreDocs[i].doc, td2.scoreDocs[i].doc);
      if (scores) {
        assertEquals(td1.scoreDocs[i].score, td2.scoreDocs[i].score, 10e-7);
      }
    }
  }

  public void testEquals() {
    Query q1 = SortedNumericDocValuesField.newSlowRangeQuery("foo", 3, 5);
    QueryUtils.checkEqual(q1, SortedNumericDocValuesField.newSlowRangeQuery("foo", 3, 5));
    QueryUtils.checkUnequal(q1, SortedNumericDocValuesField.newSlowRangeQuery("foo", 3, 6));
    QueryUtils.checkUnequal(q1, SortedNumericDocValuesField.newSlowRangeQuery("foo", 4, 5));
    QueryUtils.checkUnequal(q1, SortedNumericDocValuesField.newSlowRangeQuery("bar", 3, 5));

    Query q2 =
        SortedSetDocValuesField.newSlowRangeQuery(
            "foo", newBytesRef("bar"), newBytesRef("baz"), true, true);
    QueryUtils.checkEqual(
        q2,
        SortedSetDocValuesField.newSlowRangeQuery(
            "foo", newBytesRef("bar"), newBytesRef("baz"), true, true));
    QueryUtils.checkUnequal(
        q2,
        SortedSetDocValuesField.newSlowRangeQuery(
            "foo", newBytesRef("baz"), newBytesRef("baz"), true, true));
    QueryUtils.checkUnequal(
        q2,
        SortedSetDocValuesField.newSlowRangeQuery(
            "foo", newBytesRef("bar"), newBytesRef("bar"), true, true));
    QueryUtils.checkUnequal(
        q2,
        SortedSetDocValuesField.newSlowRangeQuery(
            "quux", newBytesRef("bar"), newBytesRef("baz"), true, true));
  }

  public void testToString() {
    Query q1 = SortedNumericDocValuesField.newSlowRangeQuery("foo", 3, 5);
    assertEquals("foo:[3 TO 5]", q1.toString());
    assertEquals("[3 TO 5]", q1.toString("foo"));
    assertEquals("foo:[3 TO 5]", q1.toString("bar"));

    Query q2 =
        SortedSetDocValuesField.newSlowRangeQuery(
            "foo", newBytesRef("bar"), newBytesRef("baz"), true, true);
    assertEquals("foo:[[62 61 72] TO [62 61 7a]]", q2.toString());
    q2 =
        SortedSetDocValuesField.newSlowRangeQuery(
            "foo", newBytesRef("bar"), newBytesRef("baz"), false, true);
    assertEquals("foo:{[62 61 72] TO [62 61 7a]]", q2.toString());
    q2 =
        SortedSetDocValuesField.newSlowRangeQuery(
            "foo", newBytesRef("bar"), newBytesRef("baz"), false, false);
    assertEquals("foo:{[62 61 72] TO [62 61 7a]}", q2.toString());
    q2 = SortedSetDocValuesField.newSlowRangeQuery("foo", newBytesRef("bar"), null, true, true);
    assertEquals("foo:[[62 61 72] TO *}", q2.toString());
    q2 = SortedSetDocValuesField.newSlowRangeQuery("foo", null, newBytesRef("baz"), true, true);
    assertEquals("foo:{* TO [62 61 7a]]", q2.toString());
    assertEquals("{* TO [62 61 7a]]", q2.toString("foo"));
    assertEquals("foo:{* TO [62 61 7a]]", q2.toString("bar"));
  }

  public void testMissingField() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter iw = new RandomIndexWriter(random(), dir);
    iw.addDocument(new Document());
    IndexReader reader = iw.getReader();
    iw.close();
    IndexSearcher searcher = newSearcher(reader);
    for (Query query :
        Arrays.asList(
            NumericDocValuesField.newSlowRangeQuery("foo", 2, 4),
            SortedNumericDocValuesField.newSlowRangeQuery("foo", 2, 4),
            SortedDocValuesField.newSlowRangeQuery(
                "foo",
                newBytesRef("abc"),
                newBytesRef("bcd"),
                random().nextBoolean(),
                random().nextBoolean()),
            SortedSetDocValuesField.newSlowRangeQuery(
                "foo",
                newBytesRef("abc"),
                newBytesRef("bcd"),
                random().nextBoolean(),
                random().nextBoolean()))) {
      Weight w = searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE, 1);
      assertNull(w.scorer(searcher.getIndexReader().leaves().get(0)));
    }
    reader.close();
    dir.close();
  }

  public void testSlowRangeQueryRewrite() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter iw = new RandomIndexWriter(random(), dir);
    IndexReader reader = iw.getReader();
    iw.close();
    IndexSearcher searcher = newSearcher(reader);

    QueryUtils.checkEqual(
        NumericDocValuesField.newSlowRangeQuery("foo", 10, 1).rewrite(searcher),
        new MatchNoDocsQuery());
    QueryUtils.checkEqual(
        NumericDocValuesField.newSlowRangeQuery("foo", Long.MIN_VALUE, Long.MAX_VALUE)
            .rewrite(searcher),
        new FieldExistsQuery("foo"));
    reader.close();
    dir.close();
  }

  public void testSortedNumericNPE() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter iw = new RandomIndexWriter(random(), dir);
    double[] nums = {
      -1.7147449030215377E-208,
      -1.6887024655302576E-11,
      1.534911516604164E113,
      0.0,
      2.6947996404505155E-166,
      -2.649722021970773E306,
      6.138239235731689E-198,
      2.3967090122610808E111
    };
    for (int i = 0; i < nums.length; ++i) {
      Document doc = new Document();
      doc.add(new SortedNumericDocValuesField("dv", NumericUtils.doubleToSortableLong(nums[i])));
      iw.addDocument(doc);
    }
    iw.commit();
    final IndexReader reader = iw.getReader();
    final IndexSearcher searcher = newSearcher(reader);
    iw.close();

    final long lo = NumericUtils.doubleToSortableLong(8.701032080293731E-226);
    final long hi = NumericUtils.doubleToSortableLong(2.0801416404385346E-41);

    Query query = SortedNumericDocValuesField.newSlowRangeQuery("dv", lo, hi);
    // TODO: assert expected matches
    searcher.search(query, searcher.reader.maxDoc(), Sort.INDEXORDER);

    // swap order, should still work
    query = SortedNumericDocValuesField.newSlowRangeQuery("dv", hi, lo);
    // TODO: assert expected matches
    searcher.search(query, searcher.reader.maxDoc(), Sort.INDEXORDER);

    reader.close();
    dir.close();
  }

  public void testSetEquals() {
    assertEquals(
        NumericDocValuesField.newSlowSetQuery("field", 17L, 42L),
        NumericDocValuesField.newSlowSetQuery("field", 17L, 42L));
    assertEquals(
        NumericDocValuesField.newSlowSetQuery("field", 17L, 42L, 32416190071L),
        NumericDocValuesField.newSlowSetQuery("field", 17L, 32416190071L, 42L));
    assertFalse(
        NumericDocValuesField.newSlowSetQuery("field", 42L)
            .equals(NumericDocValuesField.newSlowSetQuery("field2", 42L)));
    assertFalse(
        NumericDocValuesField.newSlowSetQuery("field", 17L, 42L)
            .equals(NumericDocValuesField.newSlowSetQuery("field", 17L, 32416190071L)));
  }

  public void testDuelSetVsTermsQuery() throws IOException {
    final int iters = atLeast(2);
    for (int iter = 0; iter < iters; ++iter) {
      final List<Long> allNumbers = new ArrayList<>();
      final int numNumbers = TestUtil.nextInt(random(), 1, 1 << TestUtil.nextInt(random(), 1, 10));
      for (int i = 0; i < numNumbers; ++i) {
        allNumbers.add(random().nextLong());
      }
      Directory dir = newDirectory();
      RandomIndexWriter iw = new RandomIndexWriter(random(), dir);
      final int numDocs = atLeast(100);
      for (int i = 0; i < numDocs; ++i) {
        Document doc = new Document();
        final Long number = allNumbers.get(random().nextInt(allNumbers.size()));
        doc.add(new StringField("text", number.toString(), Field.Store.NO));
        doc.add(new NumericDocValuesField("long", number));
        doc.add(new SortedNumericDocValuesField("twolongs", number));
        doc.add(new SortedNumericDocValuesField("twolongs", number * 2));
        iw.addDocument(doc);
      }
      if (numNumbers > 1 && random().nextBoolean()) {
        iw.deleteDocuments(new TermQuery(new Term("text", allNumbers.get(0).toString())));
      }
      iw.commit();
      final IndexReader reader = iw.getReader();
      final IndexSearcher searcher = newSearcher(reader);
      iw.close();

      if (reader.numDocs() == 0) {
        // may occasionally happen if all documents got the same term
        IOUtils.close(reader, dir);
        continue;
      }

      for (int i = 0; i < 100; ++i) {
        final float boost = random().nextFloat() * 10;
        final int numQueryNumbers =
            TestUtil.nextInt(random(), 1, 1 << TestUtil.nextInt(random(), 1, 8));
        Set<Long> queryNumbers = new HashSet<>();
        Set<Long> queryNumbersX2 = new HashSet<>();
        for (int j = 0; j < numQueryNumbers; ++j) {
          Long number = allNumbers.get(random().nextInt(allNumbers.size()));
          queryNumbers.add(number);
          queryNumbersX2.add(2 * number);
        }
        long[] queryNumbersArray = queryNumbers.stream().mapToLong(Long::longValue).toArray();
        long[] queryNumbersX2Array = queryNumbersX2.stream().mapToLong(Long::longValue).toArray();
        final BooleanQuery.Builder bq = new BooleanQuery.Builder();
        for (Long number : queryNumbers) {
          bq.add(new TermQuery(new Term("text", number.toString())), BooleanClause.Occur.SHOULD);
        }
        Query q1 = new BoostQuery(new ConstantScoreQuery(bq.build()), boost);

        Query q2 =
            new BoostQuery(NumericDocValuesField.newSlowSetQuery("long", queryNumbersArray), boost);
        assertSameMatches(searcher, q1, q2, true);

        Query q3 =
            new BoostQuery(
                SortedNumericDocValuesField.newSlowSetQuery("twolongs", queryNumbersArray), boost);
        assertSameMatches(searcher, q1, q3, true);

        Query q4 =
            new BoostQuery(
                SortedNumericDocValuesField.newSlowSetQuery("twolongs", queryNumbersX2Array),
                boost);
        assertSameMatches(searcher, q1, q4, true);
      }

      reader.close();
      dir.close();
    }
  }
}
