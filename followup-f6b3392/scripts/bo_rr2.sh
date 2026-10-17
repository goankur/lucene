#!/bin/bash
# 25M rerank, single-stream, os5/fanout100, 10G cgroup, nquery=10000, cold cache.
# Usage: bo_rr.sh <label> <arm A|C|D|E> "<extra jvm flags>"
set -u
V=/home/goankur/vsearch; U=$V/util; BO=$V/bo; OUT=$BO/rr; mkdir -p $OUT
L=$1; ARM=$2; X=$3; MEM=${4:-10G}; P=$OUT/$L; rm -f $P.*
LUC=$BO/lucene-$ARM; CORE=$LUC/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar
IDX=/mnt/nvme/indices/bbq1_25m_al
say() { echo "$(date -Is) $*" | tee -a $BO/rr_status.txt; }
# artifact assertions: which backoff the core jar really contains
cls=$(unzip -p $CORE org/apache/lucene/store/PrefetchBackoff.class 2>/dev/null | strings)
case $ARM in
  A) [ -z "$cls" ] || { say "$L ASSERT FAIL: arm A jar has PrefetchBackoff"; exit 1; } ;;
  C) echo "$cls" | grep -q BitMixer || { say "$L ASSERT FAIL: arm C lacks BitMixer"; exit 1; } ;;
  D) echo "$cls" | grep -q ThreadLocalRandom && ! echo "$cls" | grep -q HITS_AT_MAX_SKIP || { say "$L ASSERT FAIL: arm D"; exit 1; } ;;
  E) echo "$cls" | grep -q ThreadLocalRandom && echo "$cls" | grep -q HITS_AT_MAX_SKIP || { say "$L ASSERT FAIL: arm E"; exit 1; } ;;
esac
n=$(ls $IDX/*.vec | wc -l); a=$(for f in $IDX/*.vec; do [ $(( $(stat -c %s $f) % 4096 )) -eq 16 ] && echo x; done | wc -l)
[ "$n" -eq "$a" ] || { say "$L ASSERT FAIL: index not aligned $a/$n"; exit 1; }
CP="$U/build_pa"; for j in $(find $LUC/lucene -path "*/build/libs/*-11.0.0-SNAPSHOT.jar" | grep -vE "test-framework|tests|test-fixtures|benchmark-jmh|luke"); do CP="$CP:$j"; done
for j in $U/lib/*.jar; do CP="$CP:$j"; done
ARGS="-dim 1024 -docs /mnt/nvme/data/cohere-v3/docs.vec -search /mnt/nvme/data/cohere-v3/queries.vec -ndoc 25000000 -topK 100 -maxConn 64 -beamWidthIndex 250 -quantize -quantizeBits 1 -rerankMainField -metric dot_product -numSearchThread 16 -indexType hnsw -indexPath $IDX -nquery 10000 -overSample 5 -fanout 100"
cd $U
sudo -n sh -c "echo 128 > /sys/block/nvme1n1/queue/read_ahead_kb"
sync; sudo -n sh -c "echo 3 > /proc/sys/vm/drop_caches"
UNIT=bo-$L-$$
sudo -n systemd-run --scope -q --unit=$UNIT -p MemoryMax=$MEM -p MemorySwapMax=0 --slice=knn.slice --uid=goankur \
  env HOME=/home/goankur $HOME/jdk/corretto-25/bin/java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -Djava.util.concurrent.ForkJoinPool.common.parallelism=16 -Dknn.ioDevice=nvme1n1 -Xms4g -Xmx4g $X \
  -cp "$CP" knn.KnnGraphTester $ARGS > $P.log 2>&1 &
say "$L arm=$ARM mem=$MEM core_md5=$(md5sum $CORE | cut -c1-12) extra=[$X] unit=$UNIT"
for k in $(seq 1 600); do grep -q SEARCH_WINDOW_START $P.log 2>/dev/null && break; sleep 2; done
PID=$(systemctl show -p MainPID --value $UNIT.scope 2>/dev/null); [ -z "$PID" -o "$PID" = 0 ] && PID=$(cat /sys/fs/cgroup/knn.slice/$UNIT.scope/cgroup.procs | head -1)
grep -q SEARCH_WINDOW_START $P.log || { say "$L FAILED before search window: $(tail -3 $P.log | tr "\n" " ")"; sudo -n systemctl stop $UNIT.scope; exit 1; }
sudo -n bpftrace -e "
  tracepoint:syscalls:sys_enter_madvise /pid == $PID && args->behavior == 3/ { @madvise_willneed = count(); }
  tracepoint:syscalls:sys_enter_mincore /pid == $PID/ { @mincore = count(); }
  software:major-faults:1 /pid == $PID/ { @major_faults = count(); }
" > $P.bpf 2>&1 & BPF=$!
iostat -x -d nvme1n1 1 > $P.iostat 2>&1 & IO=$!
while sudo -n test -d /sys/fs/cgroup/knn.slice/$UNIT.scope && ! grep -q "^SUMMARY:" $P.log; do sleep 2; done
sudo -n kill -INT $BPF; sleep 3; kill $IO 2>/dev/null
sleep 2; sudo -n systemctl stop $UNIT.scope 2>/dev/null
say "$L $(grep -a LATENCY_PCT_MS $P.log) recall=$(grep -a ^SUMMARY: $P.log | cut -f2) $(grep -E "^@" $P.bpf | tr -d " " | tr "\n" " ")"
