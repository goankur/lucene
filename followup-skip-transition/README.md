# SKIP vs eviction transition (2026-10-28)

Arm S (`pr16145-eval/S-head-8e90953-skip-property`) is the backoff of PR head 8e90953 (N - 1 start, scattered
per-input counter start) on main `3bb5f43990`, with `SKIP` read from `-Dbo.SKIP` for this measurement only.

`scripts/bo_rr3.sh`: 25M rerank (500 candidates per query, single stream, nquery 10000). Warm-up and the first
5,000 timed queries run in a 20 GB cgroup, fully cached, so every file is in sampling mode. At timed query 5,000
the cgroup's `MemoryMax` drops to 10 GB (4 GB heap, rest page cache), evicting most of the cache. bpftrace prints
major faults, madvise(WILLNEED) and mincore once per second (`results/*.bpf`). Runs: SKIP 64, 256, 1024, then in
reverse order. `summary.tsv` is `scripts/rr3_table.py` output.
