#!/bin/bash
# P4: swapped cold-start pairs (arm as baseline, main as candidate) so main absorbs the cold first JVM.
BO=/home/goankur/vsearch/bo; S=$BO/final_status.txt
say() { echo "$(date -Is) $*" | tee -a $S; }
while ! grep -qE "FINAL_DONE|FINAL_FAILED" $S; do sleep 30; done
I=$(ls -d /home/goankur/vsearch/indices/wikimedium10m*)
res() { fincore -b -n -o RES,SIZE $I/index/* $I/facets/* | awk '{r+=$1; s+=$2} END {printf "%.4f", r/s}'; }
for a in G J K; do
  sync; sudo -n sh -c "echo 3 > /proc/sys/vm/drop_caches"; f=$(res); say "P4 resident before F${a}Ac: $f"
  awk -v f=$f 'BEGIN{exit !(f<0.01)}' || { say "P4 not cold"; echo P4_FAILED >> $S; exit 1; }
  $BO/bo_lu_pair6.sh F${a}Ac arm$a $a - armA A - 20 || { say "P4 F${a}Ac failed"; echo P4_FAILED >> $S; exit 1; }
done
say "P4 done"; echo P4_DONE >> $S
