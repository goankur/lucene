#!/bin/bash
# luceneutil pair with per-JVM mincore count + latency, WILLNEED addresses, and /proc/pid/maps of index files.
# Usage: bo_lu_pair3.sh <id> <bn> <bArm> <bX> <cn> <cArm> <cX> <jvms>
set -u
V=/home/goankur/vsearch; BO=$V/bo; OUT=$BO/lu; mkdir -p $OUT/$1.maps
ID=$1; say() { echo "$(date -Is) $*" | tee -a $BO/lu_status.txt; }
export JAVA_HOME=$HOME/jdk/corretto-25
cd $V/util
sudo -n bpftrace -e "
  tracepoint:syscalls:sys_enter_mincore { @mincore[pid] = count(); @s[tid] = nsecs; }
  tracepoint:syscalls:sys_exit_mincore /@s[tid]/ { @mincore_ns = hist(nsecs - @s[tid]); @mincore_tot_ns[pid] = sum(nsecs - @s[tid]); delete(@s[tid]); }
  tracepoint:syscalls:sys_enter_madvise /args->behavior == 3/ { @willneed[pid] = count(); printf(\"WN %d %lx %d\n\", pid, args->start, args->len_in); }
" > $OUT/$ID.bpf 2>&1 & BPF=$!
( while true; do
    ps -eo pid=,comm=,args= | grep "[p]erf.SearchPerfTest" | while read p c rest; do
      a=$(echo "$rest" | sed -nE "s/.*bo\/lucene-([A-Z])\/lucene\/core.*/\1/p"); echo "$rest" | grep -q "prefetchBackoff=false" && a=B
      echo "$p $a" >> $OUT/$ID.pids.raw
      [ "$c" = java ] && grep wikimedium /proc/$p/maps > $OUT/$ID.maps/$p.tmp 2>/dev/null && mv $OUT/$ID.maps/$p.tmp $OUT/$ID.maps/$p
    done; sleep 1; done ) & PL=$!
say "$ID start $2($3 $4) vs $5($6 $7) jvms=$8"
python3 -u src/python/bo_lu.py $ID $2 $BO/lucene-$3 "$4" $5 $BO/lucene-$6 "$7" $8 > $OUT/$ID.log 2>&1; rc=$?
kill $PL; sudo -n kill -INT $BPF; sleep 3
sort -u $OUT/$ID.pids.raw | awk "\$2!=\"\"" > $OUT/$ID.pids
say "$ID rc=$rc $(grep -c . $OUT/$ID.pids) jvm-pids"
