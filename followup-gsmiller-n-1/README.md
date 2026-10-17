# gsmiller's N - 1 start on PR head f6b33925a0 (2026-10-17)

Same box, harness and baseline (arm A = main) as the parent directory; arm M from `followup-f6b3392` reruns
in the same session as an anchor.

| arm | branch | what |
|---|---|---|
| Q | `pr16145-eval/Q-f6b3392-n-minus-1` | f6b33925a0 with `new AtomicInteger(preloaded ? N : N - 1)`: the first prefetch probes, a hit starts sampling |
| M | `pr16145-eval/M-f6b3392-confident` | f6b33925a0 with `new AtomicInteger(N)` |

`scripts/qm_chain.sh` runs it (pair naming as in `followup-f6b3392`: `HAQc`/`HAMc` arm takes the cold JVM,
`HQAc`/`HMAc` main takes it).
