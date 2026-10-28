#!/bin/bash
# PR #16145: fault-epoch prototype vs PR head (SKIP 64) vs main. Status in fe_status.txt.
BO=/home/goankur/vsearch/bo; S=$BO/fe_status.txt; JP=$HOME/jdk/corretto-25/bin/javap
say() { echo "$(date -Is) $*" | tee -a $S; }
fail() { say "FAILED: $*"; echo FE_FAILED >> $S; exit 1; }
export JAVA_HOME=$HOME/jdk/corretto-25
say "step 1: build main, pr-head, fault-epoch (core + JMH benchmarks)"
for v in main pr-head fault-epoch; do
  W=$BO/lucene-$v
  cp $BO/jmh/jmhkit/src/*.java $W/lucene/benchmark-jmh/src/java/org/apache/lucene/benchmark/jmh/
  (cd $W && ./gradlew -q --no-daemon jar :lucene:benchmark-jmh:assemble -x test > $BO/build_$v.log 2>&1) || fail "build $v"
  J=$W/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar
  d=/tmp/pb_$v; rm -rf $d; mkdir -p $d; (cd $d && unzip -q -o $J "org/apache/lucene/store/*")
  case $v in
    main) [ ! -f $d/org/apache/lucene/store/PrefetchBackoff.class ] || fail "main has PrefetchBackoff" ;;
    pr-head) $JP -c -p -constants $d/org/apache/lucene/store/PrefetchBackoff.class | grep -q bo.SKIP || fail "pr-head check" ;;
    fault-epoch) $JP -p $d/org/apache/lucene/store/PrefetchBackoff.class | grep -q maybeRefreshEpoch && $JP -c -p -constants $d/org/apache/lucene/store/PosixNativeAccess.class | grep -q getrusage || fail "fault-epoch check" ;;
  esac
  rm -rf $BO/jmh/root-fe/$v; mkdir -p $BO/jmh/root-fe; cp -a $W/lucene/benchmark-jmh/build/benchmarks $BO/jmh/root-fe/$v
  say "  $v: git $(git -C $W rev-parse --short HEAD), lucene-core md5 $(md5sum $J | cut -c1-12), bytecode check ok"
done

say "step 2: eviction transition on the 25M rerank (20 GB cgroup, dropped to 10 GB at timed query 5000)"
for v in pr-head fault-epoch fault-epoch pr-head; do
  r=1; [ -f $BO/transition/transition_${v}_r1.log ] && r=2
  $BO/transition_run.sh transition_${v}_r$r $v "" || fail "transition $v r$r"
done

say "step 3: luceneutil wikimedium10m (20 JVMs per side)"
I=$(ls -d /home/goankur/vsearch/indices/wikimedium10m*)
res() { fincore -b -n -o RES,SIZE $I/index/* $I/facets/* | awk '{r+=$1; s+=$2} END {printf "%.4f", r/s}'; }
hot() { find $I -type f -exec cat {} + > /dev/null; f=$(res); say "  resident before $1: $f"; awk -v f=$f 'BEGIN{exit !(f>=0.99)}' || fail "not hot $1"; }
cold() { sync; sudo -n sh -c "echo 3 > /proc/sys/vm/drop_caches"; f=$(res); say "  resident before $1: $f"; awk -v f=$f 'BEGIN{exit !(f<0.01)}' || fail "not cold $1"; }
P=$BO/bo_lu_pair7.sh
hot hot_main-vs-main;               $P hot_main-vs-main               main main - main-2 main - 20 || fail p1
hot hot_main-vs-pr-head;            $P hot_main-vs-pr-head            main main - pr-head pr-head - 20 || fail p2
hot hot_main-vs-fault-epoch;        $P hot_main-vs-fault-epoch        main main - fault-epoch fault-epoch - 20 || fail p3
cold cold_main-vs-fault-epoch;      $P cold_main-vs-fault-epoch       main main - fault-epoch fault-epoch - 20 || fail p4
cold cold_fault-epoch-vs-main;      $P cold_fault-epoch-vs-main       fault-epoch fault-epoch - main main - 20 || fail p5
cold cold_main-vs-pr-head;          $P cold_main-vs-pr-head           main main - pr-head pr-head - 20 || fail p6
cold cold_pr-head-vs-main;          $P cold_pr-head-vs-main           pr-head pr-head - main main - 20 || fail p7

say "step 4: JMH StoredFieldsPrefetchBenchmark, hot (kit from michaeljmarshall e7138cc4, -f 1)"
KIT=$BO/jmh/jmhkit/scripts
for v in main pr-head fault-epoch; do
  BENCH_ROOT=$BO/jmh/root-fe BENCH_DATA=/mnt/nvme/jmhdata BENCH_OUT=$BO/jmh/results-fe BENCH_ONLY=storedfields JAVA=$HOME/jdk/corretto-25/bin/java \
    bash $KIT/run-bench.sh $v hot -f 1 >> $BO/jmh/fe.log 2>&1 || fail "jmh $v"
  say "  JMH $v done"
done
say "all done"; echo FE_DONE >> $S
