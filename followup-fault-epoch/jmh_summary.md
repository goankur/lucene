
### hot / storedfields (ops/ms, higher is better; deltas vs main)

| benchmark | main | pr-head | fault-epoch |
|---|---:|---:|---:|
| prefetchOnly_T01 [topK=100] | 53.7 ± 1.8 | 43.0 (-19.8%) | 53.7 (+0.0%) |
| prefetchOnly_T01 [topK=10] | 545.7 ± 20.8 | 432.3 (-20.8%) | 534.4 (-2.1%) |
| prefetchOnly_T08 [topK=100] | 384.0 ± 7.0 | 348.1 (-9.4%) | 423.2 (+10.2%) |
| prefetchOnly_T08 [topK=10] | 3,810.8 ± 482.3 | 3,472.7 (-8.9%) | 4,256.1 (+11.7%) |
| retrieveNoPrefetch_T01 [topK=100] | 3.7 ± 0.1 | 3.6 (-2.2%) | 3.6 (-2.4%) |
| retrieveNoPrefetch_T01 [topK=10] | 36.5 ± 2.8 | 36.3 (-0.6%) | 36.6 (+0.3%) |
| retrieveNoPrefetch_T08 [topK=100] | 10.8 ± 0.7 | 10.7 (-0.9%) | 11.0 (+1.7%) |
| retrieveNoPrefetch_T08 [topK=10] | 107.5 ± 8.7 | 107.8 (+0.3%) | 108.6 (+1.0%) |
| retrievePrefetchThenRead_T01 [topK=100] | 3.4 ± 0.4 | 3.4 (-0.2%) | 3.4 (+0.5%) |
| retrievePrefetchThenRead_T01 [topK=10] | 34.5 ± 1.0 | 33.5 (-2.8%) | 34.5 (+0.1%) |
| retrievePrefetchThenRead_T08 [topK=100] | 10.7 ± 0.8 | 10.6 (-0.8%) | 10.8 (+1.4%) |
| retrievePrefetchThenRead_T08 [topK=10] | 107.4 ± 9.5 | 106.7 (-0.7%) | 108.8 (+1.3%) |
