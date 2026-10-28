# Fault-epoch gate (2026-10-28)

Prototype on top of PR head 8e90953's backoff (branch `pr16145-eval/fault-epoch`). A file that has seen 1024
consecutive hits stops probing until the process takes a new major page fault. The JVM-wide check reads
`getrusage(RUSAGE_SELF).ru_majflt` (Panama, like the existing madvise/posix_fadvise calls) at most once per ms,
and callers look at the clock only every 64 prefetch calls. If `ru_majflt` grew, a global epoch moves, and every
confident file probes once more. A hit confirms the file for the new epoch. A miss puts it back to probing every
call, as in the PR. If `getrusage` is not available, the epoch moves on every refresh, which gives one probe per
file per ms.

Arms (`ARMS.txt`): main, pr-head (8e90953 backoff, SKIP 64), fault-epoch.

All runs are on G6 (g6.4xlarge, 16 vCPU, 60 GiB, local NVMe, kernel 6.18.54, Corretto 25), one session, run by
`scripts/fe_chain.sh` (status log `fe_status.txt`):

1. **Eviction transition** (`scripts/transition_run.sh`, same protocol as `followup-skip-transition/`): 25M
   rerank, nquery 10000, 20 GB cgroup, `MemoryMax` drops to 10 GB at timed query 5,000. Order pr-head,
   fault-epoch, fault-epoch, pr-head. `transition_summary.tsv`, `transition_windows.txt` (rates per window after
   the drop).
2. **luceneutil wikimedium10m**, 20 JVMs per side. Hot pairs: `fincore` >= 99% resident before. Cold pairs:
   `drop_caches` before, < 1% resident; luceneutil starts the second competitor's JVM first, so each cold pair
   runs in both orders. `luceneutil_summary.txt` (from `scripts/lu_sig.py`, `lu_tasks.py`, `lu_counts.py`).
3. **JMH** `StoredFieldsPrefetchBenchmark`, hot, michaeljmarshall/lucene@e7138cc4 kit, `-f 1`. `jmh_summary.md`.
