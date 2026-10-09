#!/bin/bash
# PR #16145 final evaluation. Arms on main 3bb5f43990: A main, B = A + no backoff, K PR head dfc34c0,
# G start-confident + ThreadLocalRandom gap 64, J start-confident + page hash gap 64.
set -u
BO=/home/goankur/vsearch/bo; S=$BO/final_status.txt; JP=$HOME/jdk/corretto-25/bin/javap
say() { echo "$(date -Is) $*" | tee -a $S; }
fail() { say "FINAL FAILED: $*"; echo FINAL_FAILED >> $S; exit 1; }
export JAVA_HOME=$HOME/jdk/corretto-25
OFF="-Dlucene.store.prefetchBackoff=false"
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
