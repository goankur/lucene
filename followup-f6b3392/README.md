# Follow-up on PR head f6b33925a0 (2026-10-17)

Same box, harness and baseline (arm A = main) as the parent directory.

| arm | branch | what |
|---|---|---|
| L | `pr16145-eval/L-pr-head-f6b3392` | f6b33925a0 store files on main `3bb5f43990` |
| M | `pr16145-eval/M-f6b3392-confident` | L with every input starting confident: `new AtomicInteger(N)` in `PrefetchBackoff` |

`scripts/lm_chain.sh` runs it: build + bytecode checks, 25M rerank at a 10 GB cgroup (2 reps), luceneutil
wikimedium10m hot pairs (A vs A, A vs L, A vs M) and cold-start pairs in both JVM orders
(`GALc`/`GAMc`: the arm takes the cold JVM; `GLAc`/`GMAc`: main takes it, so deltas are main relative to the arm).
