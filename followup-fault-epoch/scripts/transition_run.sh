#!/bin/bash
# Eviction-transition test for PR #16145: 25M rerank, single stream, os5/fanout100, nquery 10000; shrink at timed query 5000.
# Warm-up + first part of the timed pass run in a 20G cgroup (fully cached, files confident). At timed
# query 5000 (SEARCH_PROGRESS 5000/10000), MemoryMax drops to 10G, which evicts most of the
# page cache. bpftrace prints major faults / madvise(WILLNEED) / mincore once per second.
# Usage: transition_run.sh <label> <variant: main, pr-head, fault-epoch> "<extra jvm flags>"
set -u
V=/home/goankur/vsearch; U=$V/util; BO=$V/bo; OUT=$BO/transition; mkdir -p $OUT
L=$1; ARM=$2; X=$3; P=$OUT/$L; rm -f $P.*
LUC=$BO/lucene-$ARM; CORE=$LUC/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar
IDX=/mnt/nvme/indices/bbq1_25m_al
say() { echo "$(date -Is) $*" | tee -a $BO/transition_status.txt; }
n=$(ls $IDX/*.vec | wc -l); a=$(for f in $IDX/*.vec; do [ $(( $(stat -c %s $f) % 4096 )) -eq 16 ] && echo x; done | wc -l)
[ "$n" -eq "$a" ] || { say "$L ASSERT FAIL: index not aligned $a/$n"; exit 1; }
CP="$U/build_pa"; for j in $(find $LUC/lucene -path "*/build/libs/*-11.0.0-SNAPSHOT.jar" | grep -vE "test-framework|tests|test-fixtures|benchmark-jmh|luke"); do CP="$CP:$j"; done
for j in $U/lib/*.jar; do CP="$CP:$j"; done
ARGS="-dim 1024 -docs /mnt/nvme/data/cohere-v3/docs.vec -search /mnt/nvme/data/cohere-v3/queries.vec -ndoc 25000000 -topK 100 -maxConn 64 -beamWidthIndex 250 -quantize -quantizeBits 1 -rerankMainField -metric dot_product -numSearchThread 16 -indexType hnsw -indexPath $IDX -nquery 10000 -overSample 5 -fanout 100"
cd $U
sudo -n sh -c "echo 128 > /sys/block/nvme1n1/queue/read_ahead_kb"
sync; sudo -n sh -c "echo 3 > /proc/sys/vm/drop_caches"
UNIT=bo-$L-$$
sudo -n systemd-run --scope -q --unit=$UNIT -p MemoryMax=20G -p MemorySwapMax=0 --slice=knn.slice --uid=goankur \
  env HOME=/home/goankur $HOME/jdk/corretto-25/bin/java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -Djava.util.concurrent.ForkJoinPool.common.parallelism=16 -Dknn.ioDevice=nvme1n1 -Xms4g -Xmx4g $X \
  -cp "$CP" knn.KnnGraphTester $ARGS > $P.log 2>&1 &
say "$L arm=$ARM core_md5=$(md5sum $CORE | cut -c1-12) extra=[$X] unit=$UNIT"
for k in $(seq 1 900); do grep -q SEARCH_WINDOW_START $P.log 2>/dev/null && break; sleep 1; done
grep -q SEARCH_WINDOW_START $P.log || { say "$L FAILED before search window: $(tail -3 $P.log | tr "\n" " ")"; sudo -n systemctl stop $UNIT.scope; exit 1; }
PID=$(cat /sys/fs/cgroup/knn.slice/$UNIT.scope/cgroup.procs | head -1)
sudo -n bpftrace -e "
  BEGIN { @f = 0; @w = 0; @m = 0; }
  tracepoint:syscalls:sys_enter_madvise /pid == $PID && args->behavior == 3/ { @w = @w + 1; }
  tracepoint:syscalls:sys_enter_mincore /pid == $PID/ { @m = @m + 1; }
  software:major-faults:1 /pid == $PID/ { @f = @f + 1; }
  interval:s:1 { printf(\"SEC %d majflt=%d willneed=%d mincore=%d\n\", elapsed / 1000000000, @f, @w, @m); @f = 0; @w = 0; @m = 0; }
" > $P.bpf 2>&1 & BPF=$!
for k in $(seq 1 6000); do grep -q "SEARCH_PROGRESS 5000/" $P.log 2>/dev/null && break; sleep 0.2; done
sudo -n systemctl set-property --runtime $UNIT.scope MemoryMax=10G
say "$L SHRINK to 10G at $(grep -a "SEARCH_PROGRESS 5000/" $P.log | head -1) bpf_elapsed_line=$(grep -c ^SEC $P.bpf)"
while sudo -n test -d /sys/fs/cgroup/knn.slice/$UNIT.scope && ! grep -q "^SUMMARY:" $P.log; do sleep 1; done
C=$(pgrep -P $BPF -x bpftrace); sudo -n kill -INT ${C:-$BPF} 2>/dev/null; sleep 2
sudo -n systemctl stop $UNIT.scope 2>/dev/null
say "$L $(grep -a LATENCY_PCT_MS $P.log) $(grep -a 'warmup recall' $P.log)"
