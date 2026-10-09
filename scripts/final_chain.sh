#!/bin/bash
# PR #16145 final evaluation. Arms on main 3bb5f43990: A main, B = A + no backoff, K PR head dfc34c0,
# G start-confident + ThreadLocalRandom gap 64, J start-confident + page hash gap 64.
set -u
BO=/home/goankur/vsearch/bo; S=$BO/final_status.txt; JP=$HOME/jdk/corretto-25/bin/javap
say() { echo "$(date -Is) $*" | tee -a $S; }
fail() { say "FINAL FAILED: $*"; echo FINAL_FAILED >> $S; exit 1; }
export JAVA_HOME=$HOME/jdk/corretto-25
say "P0 build arms + JMH benchmarks"
for a in A K G J; do
  W=$BO/lucene-$a
  cp $BO/jmh/jmhkit/src/*.java $W/lucene/benchmark-jmh/src/java/org/apache/lucene/benchmark/jmh/
  (cd $W && ./gradlew -q --no-daemon jar :lucene:benchmark-jmh:assemble -x test > $BO/build_final_$a.log 2>&1) || fail "build $a"
  J=$W/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar
  d=/tmp/pb_$a; rm -rf $d; mkdir -p $d; (cd $d && unzip -q -o $J "org/apache/lucene/store/*" 2>/dev/null)
  c=$d/org/apache/lucene/store/PrefetchBackoff.class
  case $a in
    A) [ ! -f $c ] || fail "A has PrefetchBackoff" ;;
    K) $JP -c -p $c | grep -q "PrefetchBackoff(boolean)" && $JP -c -p $c | grep -q ThreadLocalRandom || fail "K bytecode" ;;
    G) $JP -c -p $c | grep -A10 "PrefetchBackoff();" | grep -q "sipush *1024" && $JP -c -p $c | grep -q ThreadLocalRandom || fail "G bytecode" ;;
    J) $JP -c -p $c | grep -A12 "PrefetchBackoff(boolean);" | grep -q "sipush *1024" && $JP -c -p $c | grep -q mix64 && ! $JP -c -p $c | grep -q ThreadLocalRandom || fail "J bytecode" ;;
  esac
  rm -rf $BO/jmh/root/$a; cp -a $W/lucene/benchmark-jmh/build/benchmarks $BO/jmh/root/$a
  cmp -s $J $BO/jmh/root/$a/lucene-core-11.0.0-SNAPSHOT.jar || fail "JMH dir core jar differs for $a"
  say "P0 arm $a git=$(git -C $W rev-parse --short HEAD) core_md5=$(md5sum $J | cut -c1-12) bytecode ok"
done
m() { (cd "$1" && find . -maxdepth 1 -type f -printf "%f %s\n" | sort); }
[ "$(m /mnt/nvme/indices/bbq1_25m_al)" = "$(m /home/goankur/ebs_test/indices/bbq1_25m_al)" ] || fail "25M index differs from EBS copy"
say "P0 25M index manifest matches EBS"

say "P1 25M rerank: 5 arms x {10G,20G} x 2 reps"
OFF="-Dlucene.store.prefetchBackoff=false"
rr() { local arm=$1 lab=$2 mem=$3; case $arm in B) $BO/bo_rr2.sh $lab A "$OFF" $mem ;; *) $BO/bo_rr2.sh $lab $arm "" $mem ;; esac; }
for r in 1 2; do
  if [ $r = 1 ]; then ORDER="A B K G J"; else ORDER="J G K B A"; fi
  for x in $ORDER; do
    rr $x F10_${x}_r$r 10G || fail "rr $x 10G r$r"
    rr $x F20_${x}_r$r 20G || fail "rr $x 20G r$r"
  done
done
say "P1 done"

say "P2 wikimedium10m pairs"
I=$(ls -d /home/goankur/vsearch/indices/wikimedium10m*)
res() { fincore -b -n -o RES,SIZE $I/index/* $I/facets/* | awk '{r+=$1; s+=$2} END {printf "%.4f", r/s}'; }
hot() { find $I -type f -exec cat {} + > /dev/null; f=$(res); say "P2 resident before $1: $f"; awk -v f=$f 'BEGIN{exit !(f>=0.99)}' || fail "not hot $1"; }
cold() { sync; sudo -n sh -c "echo 3 > /proc/sys/vm/drop_caches"; f=$(res); say "P2 resident before $1: $f"; awk -v f=$f 'BEGIN{exit !(f<0.01)}' || fail "not cold $1"; }
hot FAA;  $BO/bo_lu_pair6.sh FAA  armA A - armA2 A - 20 || fail FAA
hot FAB;  $BO/bo_lu_pair6.sh FAB  armA A - armB A "$OFF" 20 || fail FAB
hot FAK;  $BO/bo_lu_pair6.sh FAK  armA A - armK K - 20 || fail FAK
hot FAG;  $BO/bo_lu_pair6.sh FAG  armA A - armG G - 20 || fail FAG
hot FAJ;  $BO/bo_lu_pair6.sh FAJ  armA A - armJ J - 20 || fail FAJ
cold FAKc; $BO/bo_lu_pair6.sh FAKc armA A - armK K - 20 || fail FAKc
cold FAGc; $BO/bo_lu_pair6.sh FAGc armA A - armG G - 20 || fail FAGc
cold FAJc; $BO/bo_lu_pair6.sh FAJc armA A - armJ J - 20 || fail FAJc
say "P2 done"

say "P3 JMH (kit from michaeljmarshall e7138cc4, -f 1): variants A K G J x hot pressure cold hot2"
KIT=$BO/jmh/jmhkit/scripts; export BENCH_ROOT=$BO/jmh/root BENCH_DATA=/mnt/nvme/jmhdata JAVA=$HOME/jdk/corretto-25/bin/java
for s in hot pressure cold; do
  for v in A K G J; do BENCH_OUT=$BO/jmh/results bash $KIT/run-bench.sh $v $s -f 1 >> $BO/jmh/all.log 2>&1 || say "P3 FAILED $v $s"; say "P3 $v $s done"; done
done
for v in A K G J; do BENCH_OUT=$BO/jmh/results-hot2 bash $KIT/run-bench.sh $v hot -f 1 >> $BO/jmh/all.log 2>&1 || say "P3 FAILED $v hot2"; say "P3 $v hot2 done"; done
say "P3 done"; echo FINAL_DONE >> $S
