## cold-mongo@1M

A million accounts on MongoDB, uniform keys, so the caches miss (cold): E1 writes and E4 point reads at fixed rates.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 3.6 ms | 21.5 ms | 68.1 ms | n/a | 240.6 ms | 0.41 cores | kafka 51% · mongo 95% | 0.18 of 1 | mongo 0.95 | 9.36 | 234.1 KB | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.9 ms | 2.5 ms | 6.2 ms | n/a | 108.5 ms | 0.25 cores | kafka 53% · mongo 43% | 0.25 of 1 | kafka 0.53 | 2.44 | 71.1 KB | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cold-mongo@1M | d1-head-e1-200 | steady | **p99 > 10× p50** | p50 3.6 ms, p99 68.1 ms |

