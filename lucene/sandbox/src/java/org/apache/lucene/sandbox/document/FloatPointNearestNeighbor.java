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

package org.apache.lucene.sandbox.document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.PointTree;
import org.apache.lucene.internal.hppc.IntArrayList;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.util.Bits;

/**
 * KNN search on top of N dimensional indexed float points.
 *
 * @lucene.experimental
 */
public class FloatPointNearestNeighbor {

  /**
   * @param distanceSquared The closest possible distance^2 of all points in this cell
   */
  record Cell(
      PointTree index, int readerIndex, byte[] minPacked, byte[] maxPacked, double distanceSquared)
      implements Comparable<Cell> {
    Cell(
        PointTree index,
        int readerIndex,
        byte[] minPacked,
        byte[] maxPacked,
        double distanceSquared) {
      this.index = index;
      this.readerIndex = readerIndex;
      this.minPacked = minPacked.clone();
      this.maxPacked = maxPacked.clone();
      this.distanceSquared = distanceSquared;
    }

    @Override
    public int compareTo(Cell other) {
      return Double.compare(distanceSquared, other.distanceSquared);
    }

    @Override
    public String toString() {
      return "Cell(readerIndex="
          + readerIndex
          + " "
          + index.toString()
          + " distanceSquared="
          + distanceSquared
          + ")";
    }
  }

  private static class NearestVisitor implements PointValues.IntersectVisitor {
    int curDocBase;
    Bits curLiveDocs;
    final int topN;
    final PriorityQueue<NearestHit> hitQueue;
    final float[] origin;
    private final int dims;
    double bottomNearestDistanceSquared = Double.POSITIVE_INFINITY;
    int bottomNearestDistanceDoc = Integer.MAX_VALUE;

    public NearestVisitor(PriorityQueue<NearestHit> hitQueue, int topN, float[] origin) {
      this.hitQueue = hitQueue;
      this.topN = topN;
      this.origin = origin;
      this.dims = origin.length;
    }

    @Override
    public void visit(int docID) {
      throw new AssertionError();
    }

    @Override
    public void visit(int docID, byte[] packedValue) {
      // System.out.println("visit docID=" + docID + " liveDocs=" + curLiveDocs);;
      if (curLiveDocs != null && curLiveDocs.get(docID) == false) {
        return;
      }

      double distanceSquared = 0.0d;
      for (int d = 0, offset = 0; d < dims; ++d, offset += Float.BYTES) {
        double diff = (double) FloatPoint.decodeDimension(packedValue, offset) - (double) origin[d];
        distanceSquared += diff * diff;
        if (distanceSquared > bottomNearestDistanceSquared) {
          return;
        }
      }

      // System.out.println("    visit docID=" + docID + " distanceSquared=" + distanceSquared + "
      // value: " + Arrays.toString(docPoint));

      int fullDocID = curDocBase + docID;

      if (hitQueue.size() == topN) { // queue already full
        if (distanceSquared == bottomNearestDistanceSquared
            && fullDocID > bottomNearestDistanceDoc) {
          return;
        }
        NearestHit bottom = hitQueue.poll();
        // System.out.println("      bottom distanceSquared=" + bottom.distanceSquared);
        bottom.docID = fullDocID;
        bottom.distanceSquared = distanceSquared;
        hitQueue.offer(bottom);
        updateBottomNearestDistance();
        // System.out.println("      ** keep1, now bottom=" + bottom);
      } else {
        NearestHit hit = new NearestHit();
        hit.docID = fullDocID;
        hit.distanceSquared = distanceSquared;
        hitQueue.offer(hit);
        if (hitQueue.size() == topN) {
          updateBottomNearestDistance();
        }
        // System.out.println("      ** keep2, new addition=" + hit);
      }
    }

    private void updateBottomNearestDistance() {
      NearestHit newBottom = hitQueue.peek();
      bottomNearestDistanceSquared = newBottom.distanceSquared;
      bottomNearestDistanceDoc = newBottom.docID;
    }

    @Override
    public PointValues.Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
      if (hitQueue.size() == topN
          && pointToRectangleDistanceSquared(minPackedValue, maxPackedValue, origin)
              > bottomNearestDistanceSquared) {
        return PointValues.Relation.CELL_OUTSIDE_QUERY;
      }
      return PointValues.Relation.CELL_CROSSES_QUERY;
    }
  }

  /** Holds one hit from {@link FloatPointNearestNeighbor#nearest} */
  static class NearestHit {
    public int docID;
    public double distanceSquared;

    @Override
    public String toString() {
      return "NearestHit(docID=" + docID + " distanceSquared=" + distanceSquared + ")";
    }
  }

  private static NearestHit[] nearest(
      List<PointValues> readers,
      List<Bits> liveDocs,
      IntArrayList docBases,
      final int topN,
      float[] origin)
      throws IOException {

    // System.out.println("NEAREST: readers=" + readers + " liveDocs=" + liveDocs + " origin: " +
    // Arrays.toString(origin));

    // Holds closest collected points seen so far:
    // TODO: if we used lucene's PQ we could just updateTop instead of poll/offer:
    final PriorityQueue<NearestHit> hitQueue =
        new PriorityQueue<>(
            topN,
            (a, b) -> {
              // sort by opposite distance natural order
              int cmp = Double.compare(a.distanceSquared, b.distanceSquared);
              return cmp != 0 ? -cmp : b.docID - a.docID; // tie-break by higher docID
            });

    // Holds all cells, sorted by closest to the point:
    PriorityQueue<Cell> cellQueue = new PriorityQueue<>();

    NearestVisitor visitor = new NearestVisitor(hitQueue, topN, origin);

    // Add root cell for each reader into the queue:
    for (int i = 0; i < readers.size(); ++i) {
      PointValues reader = readers.get(i);
      byte[] minPackedValue = reader.getMinPackedValue();
      byte[] maxPackedValue = reader.getMaxPackedValue();
      PointTree indexTree = reader.getPointTree();

      cellQueue.offer(
          new Cell(
              indexTree,
              i,
              reader.getMinPackedValue(),
              reader.getMaxPackedValue(),
              pointToRectangleDistanceSquared(minPackedValue, maxPackedValue, origin)));
    }

    while (cellQueue.size() > 0) {
      Cell cell = cellQueue.poll();
      // System.out.println("  visit " + cell);

      if (cell.distanceSquared > visitor.bottomNearestDistanceSquared) {
        break;
      }

      if (cell.index.moveToChild() == false) {
        // System.out.println("    leaf");
        // Leaf block: visit all points and possibly collect them:
        visitor.curDocBase = docBases.get(cell.readerIndex);
        visitor.curLiveDocs = liveDocs.get(cell.readerIndex);
        cell.index.visitDocValues(visitor);
        // reader.visitLeafBlockValues(cell.index, states.get(cell.readerIndex));

        // assert hitQueue.peek().distanceSquared >= cell.distanceSquared;
        // System.out.println("    now " + hitQueue.size() + " hits");
      } else {

        // we must clone the index so that we we can recurse left and right "concurrently":
        PointTree newIndex = cell.index.clone();

        double distanceLeft =
            pointToRectangleDistanceSquared(
                newIndex.getMinPackedValue(), newIndex.getMaxPackedValue(), origin);
        if (distanceLeft <= visitor.bottomNearestDistanceSquared) {
          cellQueue.offer(
              new Cell(
                  newIndex,
                  cell.readerIndex,
                  newIndex.getMinPackedValue(),
                  newIndex.getMaxPackedValue(),
                  distanceLeft));
        }

        if (cell.index.moveToSibling()) {
          double distanceRight =
              pointToRectangleDistanceSquared(
                  cell.index.getMinPackedValue(), cell.index.getMaxPackedValue(), origin);
          if (distanceRight <= visitor.bottomNearestDistanceSquared) {
            cellQueue.offer(
                new Cell(
                    cell.index,
                    cell.readerIndex,
                    cell.index.getMinPackedValue(),
                    cell.index.getMaxPackedValue(),
                    distanceRight));
          }
        }
      }
    }

    NearestHit[] hits = new NearestHit[hitQueue.size()];
    int downTo = hitQueue.size() - 1;
    while (hitQueue.size() != 0) {
      hits[downTo] = hitQueue.poll();
      downTo--;
    }
    // System.out.println(visitor.comp);
    return hits;
  }

  private static double pointToRectangleDistanceSquared(
      byte[] minPackedValue, byte[] maxPackedValue, float[] value) {
    double sumOfSquaredDiffs = 0.0d;
    for (int i = 0, offset = 0; i < value.length; ++i, offset += Float.BYTES) {
      double min = FloatPoint.decodeDimension(minPackedValue, offset);
      if (value[i] < min) {
        double diff = min - (double) value[i];
        sumOfSquaredDiffs += diff * diff;
        continue;
      }
      double max = FloatPoint.decodeDimension(maxPackedValue, offset);
      if (value[i] > max) {
        double diff = max - (double) value[i];
        sumOfSquaredDiffs += diff * diff;
      }
    }
    return sumOfSquaredDiffs;
  }

  public static TopFieldDocs nearest(
      IndexSearcher searcher, String field, int topN, float... origin) throws IOException {
    if (topN < 1) {
      throw new IllegalArgumentException("topN must be at least 1; got " + topN);
    }
    if (field == null) {
      throw new IllegalArgumentException("field must not be null");
    }
    if (searcher == null) {
      throw new IllegalArgumentException("searcher must not be null");
    }
    List<PointValues> readers = new ArrayList<>();
    IntArrayList docBases = new IntArrayList();
    List<Bits> liveDocs = new ArrayList<>();
    int totalHits = 0;
    for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
      PointValues points = leaf.reader().getPointValues(field);
      if (points != null) {
        totalHits += points.getDocCount();
        readers.add(points);
        docBases.add(leaf.docBase);
        liveDocs.add(leaf.reader().getLiveDocs());
      }
    }

    NearestHit[] hits = nearest(readers, liveDocs, docBases, topN, origin);

    // Convert to TopFieldDocs:
    ScoreDoc[] scoreDocs = new ScoreDoc[hits.length];
    for (int i = 0; i < hits.length; i++) {
      NearestHit hit = hits[i];
      scoreDocs[i] =
          new FieldDoc(hit.docID, 0.0f, new Object[] {(float) Math.sqrt(hit.distanceSquared)});
    }
    return new TopFieldDocs(new TotalHits(totalHits, TotalHits.Relation.EQUAL_TO), scoreDocs, null);
  }
}
