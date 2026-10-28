#!/bin/bash
BO=/home/goankur/vsearch/bo; S=$BO/rr3_status.txt; JP=$HOME/jdk/corretto-25/bin/javap
say() { echo "$(date -Is) $*" | tee -a $S; }
export JAVA_HOME=$HOME/jdk/corretto-25
W=$BO/lucene-S
(cd $W && ./gradlew -q --no-daemon jar -x test > $BO/build_S.log 2>&1) || { say "build S failed"; exit 1; }
d=/tmp/pb_S; rm -rf $d; mkdir -p $d; (cd $d && unzip -q -o $W/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar "org/apache/lucene/store/*")
$JP -c -p -constants $d/org/apache/lucene/store/PrefetchBackoff.class > $d/javap.txt
grep -q 'bo.SKIP' $d/javap.txt && grep -q 'int nextSeed()' $d/javap.txt && ! grep -q ThreadLocalRandom $d/javap.txt || { say "S bytecode check failed"; exit 1; }
say "arm S git=$(git -C $W rev-parse --short HEAD) core_md5=$(md5sum $W/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar | cut -c1-12) bytecode ok"
for s in 64 256 1024 1024 256 64; do
  r=1; [ -f $BO/rr3/T${s}_r1.log ] && r=2
  $BO/bo_rr3.sh T${s}_r$r S "-Dbo.SKIP=$s" || { say "run T${s}_r$r failed"; exit 1; }
done
say "SKIP_DONE"
