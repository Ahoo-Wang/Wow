## cold-mongo@1M

A million accounts on MongoDB, uniform keys, so the caches miss (cold): E1 writes and E4 point reads at fixed rates.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

- `head`: build e3a4e937aa

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 3.6 ms | 72.7 ms | 154.6 ms | n/a | 234.5 ms | 0.37 cores | kafka 46% · mongo 77% | 0.13 of 1 | mongo 0.77 | 8.00 | 257.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.7 ms | 2.7 ms | 4.9 ms | n/a | 30.5 ms | 0.20 cores | kafka 48% · mongo 41% | 0.14 of 1 | kafka 0.48 | 2.18 | 76.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cold-mongo@1M | d1-head-e1-200 | steady | **p99 > 10× p50** | p50 3.6 ms, p99 154.6 ms |

