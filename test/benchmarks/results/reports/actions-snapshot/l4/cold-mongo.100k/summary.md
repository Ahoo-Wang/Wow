## cold-mongo@1M

A million accounts on MongoDB, uniform keys, so the caches miss (cold): E1 writes and E4 point reads at fixed rates.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 4.3 ms | 20.0 ms | 43.3 ms | n/a | 101.9 ms | 0.45 cores | kafka 56% · mongo 97% | 0.18 of 1 | mongo 0.97 | 9.89 | 290.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.9 ms | 3.5 ms | 6.7 ms | n/a | 45.3 ms | 0.25 cores | kafka 56% · mongo 48% | 0.18 of 1 | kafka 0.56 | 2.57 | 76.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

