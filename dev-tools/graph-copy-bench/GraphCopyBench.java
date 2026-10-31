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

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Random;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.ReadAdvice;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.InitializedHnswGraphBuilder;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorerSupplier;
import org.apache.lucene.util.hnsw.UpdateableRandomVectorScorer;

/**
 * Times InitializedHnswGraphBuilder.initGraph on the on-disk HNSW graph of one segment, with no
 * deletions, as a merge does when it reuses the graph of its largest segment.
 *
 * <p>The scorer's setScoringOrdinal(node) reads node's query record from a scratch file the way
 * the Lucene104 asymmetric merge scorer does (OffHeapScalarQuantizedVectorValues.vectorValue):
 * seek to node * RECORD_BYTES, read the packed vector, 3 floats and an int. RECORD_BYTES is the
 * SINGLE_BIT_QUERY_NIBBLE query record at dim 4096: 2048 packed bytes + 16 bytes of corrections.
 * The scratch file is opened through MMapDirectory with ReadAdvice.RANDOM, or with the default read
 * advice if the fifth argument is "default". Nothing scores during
 * the copy, so score() is not supported.
 *
 * <p>Usage: java -cp lucene-core.jar:. GraphCopyBench info|prepare|run indexDir segment scratchDir
 */
public class GraphCopyBench {
  static final int PACKED_BYTES = 2048;
  static final int RECORD_BYTES = PACKED_BYTES + 3 * Float.BYTES + Integer.BYTES;
  static final int BEAM_WIDTH = 256;

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    try (MMapDirectory indexDir = new MMapDirectory(Path.of(args[1]));
        GraphSource source = openGraph(indexDir, args[2])) {
      String field = source.field();
      HnswGraph graph = source.reader().getGraph(field);
      int size = graph.size();
      Path records = Path.of(args[3], "records.bin");
      StringBuilder levels = new StringBuilder();
      for (int level = 0; level < graph.numLevels(); level++) {
        levels.append(' ').append(graph.getNodesOnLevel(level).size());
      }
      System.out.println("field=" + field + " size=" + size + " M=" + graph.maxConn());
      System.out.println("nodes per level:" + levels + " record bytes=" + RECORD_BYTES);
      if (mode.equals("prepare")) {
        writeRecords(records, (long) size * RECORD_BYTES);
      }
      if (mode.equals("run") == false) {
        return;
      }

      try (MMapDirectory scratchDir = new MMapDirectory(Path.of(args[3]))) {
        // "random" (as a large merge may set for its query file) or "default" (MMapDirectory default)
        String advice = args.length > 4 ? args[4] : "random";
        if (advice.equals("random")) {
          scratchDir.setReadAdvice((_, _) -> Optional.of(ReadAdvice.RANDOM));
        }
        System.out.println("record file read advice=" + advice);
        try (IndexInput in = scratchDir.openInput("records.bin", IOContext.DEFAULT)) {
          RecordReadingSupplier supplier = new RecordReadingSupplier(in, size);
          int[] ordMap = new int[size];
          for (int i = 0; i < size; i++) {
            ordMap[i] = i;
          }
          long start = System.nanoTime();
          OnHeapHnswGraph copy =
              InitializedHnswGraphBuilder.initGraph(graph, ordMap, size, BEAM_WIDTH, supplier);
          long ms = (System.nanoTime() - start) / 1_000_000;
          System.out.println(
              "copy ms="
                  + ms
                  + " setScoringOrdinal calls="
                  + supplier.calls[0]
                  + " copied size="
                  + copy.size()
                  + " checksum="
                  + supplier.checksum[0]);
        }
      }
    }
  }

  /** The HNSW graph part of a segment's vectors format, and the vector field it holds. */
  record GraphSource(Lucene99HnswVectorsReader reader, String field) implements Closeable {
    @Override
    public void close() throws IOException {
      reader.close();
    }
  }

  /** Opens only the HNSW graph part of the segment's vectors format; flat vectors are not used. */
  static GraphSource openGraph(MMapDirectory dir, String segment) throws IOException {
    SegmentInfos infos = SegmentInfos.readLatestCommit(dir);
    for (SegmentCommitInfo sci : infos) {
      if (sci.info.name.equals(segment)) {
        Codec codec = sci.info.getCodec();
        FieldInfos fieldInfos = codec.fieldInfosFormat().read(dir, sci.info, "", IOContext.DEFAULT);
        for (FieldInfo fi : fieldInfos) {
          if (fi.hasVectorValues()) {
            String suffix =
                fi.getAttribute(PerFieldKnnVectorsFormat.PER_FIELD_FORMAT_KEY)
                    + "_"
                    + fi.getAttribute(PerFieldKnnVectorsFormat.PER_FIELD_SUFFIX_KEY);
            SegmentReadState state =
                new SegmentReadState(dir, sci.info, fieldInfos, IOContext.DEFAULT, suffix);
            return new GraphSource(new Lucene99HnswVectorsReader(state, null), fi.name);
          }
        }
      }
    }
    throw new IllegalArgumentException("no vector field in segment " + segment);
  }

  /** Writes the scratch file once: content does not matter, only the access pattern. */
  static void writeRecords(Path path, long bytes) throws IOException {
    ByteBuffer chunk = ByteBuffer.allocateDirect(64 << 20);
    byte[] random = new byte[chunk.capacity()];
    new Random(42).nextBytes(random);
    chunk.put(random).flip();
    try (FileChannel ch =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      for (long written = 0; written < bytes; ) {
        chunk.clear().limit((int) Math.min(chunk.capacity(), bytes - written));
        written += ch.write(chunk);
      }
      ch.force(true);
    }
    System.out.println("wrote " + path + " bytes=" + bytes);
  }

  /** Each scorer reads one query record per setScoringOrdinal call. */
  record RecordReadingSupplier(IndexInput records, int size, long[] calls, long[] checksum)
      implements RandomVectorScorerSupplier {
    RecordReadingSupplier(IndexInput records, int size) {
      this(records, size, new long[1], new long[1]);
    }

    @Override
    public UpdateableRandomVectorScorer scorer() {
      IndexInput in = records.clone();
      byte[] packed = new byte[PACKED_BYTES];
      float[] corrections = new float[3];
      return new UpdateableRandomVectorScorer() {
        @Override
        public void setScoringOrdinal(int node) throws IOException {
          calls[0]++;
          in.seek((long) node * RECORD_BYTES);
          in.readBytes(packed, 0, PACKED_BYTES);
          in.readFloats(corrections, 0, 3);
          checksum[0] += packed[node & (PACKED_BYTES - 1)] + in.readInt();
        }

        @Override
        public float score(int node) {
          throw new UnsupportedOperationException();
        }

        @Override
        public int maxOrd() {
          return size;
        }
      };
    }

    @Override
    public RandomVectorScorerSupplier copy() {
      return this;
    }
  }
}
