#!/bin/bash
# PR #16145 follow-up on f6b33925a0: arm Q = f6b33925a0 + start at N-1 (gsmiller), arm M = start at N (anchor).
set -u
BO=/home/goankur/vsearch/bo; S=$BO/qm_status.txt; JP=$HOME/jdk/corretto-25/bin/javap
say() { echo "$(date -Is) $*" | tee -a $S; }
fail() { say "QM FAILED: $*"; echo QM_FAILED >> $S; exit 1; }
export JAVA_HOME=$HOME/jdk/corretto-25
say "P0 build Q (M already built)"
for a in Q M; do
  W=$BO/lucene-$a
  (cd $W && ./gradlew -q --no-daemon jar -x test > $BO/build_qm_$a.log 2>&1) || fail "build $a"
  J=$W/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar
  d=/tmp/pb_$a; rm -rf $d; mkdir -p $d; (cd $d && unzip -q -o $J "org/apache/lucene/store/*")
  c=$d/org/apache/lucene/store/PrefetchBackoff.class
  $JP -c -p $c > $d/javap.txt
  grep -q "int nextSeed()" $d/javap.txt || fail "$a lacks nextSeed"
  grep -q ThreadLocalRandom $d/javap.txt && fail "$a uses ThreadLocalRandom"
  ctor=$(awk '/PrefetchBackoff\(boolean\);/{f=1} f&&/return/{print; exit} f' $d/javap.txt)
  case $a in
    Q) echo "$ctor" | grep -q "ifeq" && echo "$ctor" | grep -q "sipush *1023" || fail "Q ctor does not start at N-1" ;;
    M) echo "$ctor" | grep -q "ifeq" && fail "M ctor still conditional"; echo "$ctor" | grep -q "sipush *1024" || fail "M ctor does not start at N" ;;
  esac
  say "P0 arm $a git=$(git -C $W rev-parse --short HEAD) core_md5=$(md5sum $J | cut -c1-12) bytecode ok"
done
m() { (cd "$1" && find . -maxdepth 1 -type f -printf "%f %s\n" | sort); }
[ "$(m /mnt/nvme/indices/bbq1_25m_al)" = "$(m /home/goankur/ebs_test/indices/bbq1_25m_al)" ] || fail "25M index differs from EBS copy"

say "P1 25M rerank, cgroup 10G, 2 reps"
for x in Q M M Q; do
  r=1; [ -f $BO/rr/H10_${x}_r1.log ] && r=2
  $BO/bo_rr2.sh H10_${x}_r$r $x "" 10G || fail "rr $x r$r"
done
say "P1 done"

say "P2 wikimedium10m"
I=$(ls -d /home/goankur/vsearch/indices/wikimedium10m*)
res() { fincore -b -n -o RES,SIZE $I/index/* $I/facets/* | awk '{r+=$1; s+=$2} END {printf "%.4f", r/s}'; }
hot() { find $I -type f -exec cat {} + > /dev/null; f=$(res); say "P2 resident before $1: $f"; awk -v f=$f 'BEGIN{exit !(f>=0.99)}' || fail "not hot $1"; }
cold() { sync; sudo -n sh -c "echo 3 > /proc/sys/vm/drop_caches"; f=$(res); say "P2 resident before $1: $f"; awk -v f=$f 'BEGIN{exit !(f<0.01)}' || fail "not cold $1"; }
hot HAA;  $BO/bo_lu_pair6.sh HAA  armA A - armA2 A - 20 || fail HAA
hot HAQ;  $BO/bo_lu_pair6.sh HAQ  armA A - armQ Q - 20 || fail HAQ
hot HAM;  $BO/bo_lu_pair6.sh HAM  armA A - armM M - 20 || fail HAM
cold HAQc; $BO/bo_lu_pair6.sh HAQc armA A - armQ Q - 20 || fail HAQc
cold HQAc; $BO/bo_lu_pair6.sh HQAc armQ Q - armA A - 20 || fail HQAc
cold HAMc; $BO/bo_lu_pair6.sh HAMc armA A - armM M - 20 || fail HAMc
cold HMAc; $BO/bo_lu_pair6.sh HMAc armM M - armA A - 20 || fail HMAc
say "P2 done"; echo QM_DONE >> $S
