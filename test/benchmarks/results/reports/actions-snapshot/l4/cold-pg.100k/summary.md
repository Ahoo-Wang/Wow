## cold-pg@1M

A million accounts on PostgreSQL, as cold-mongo.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 1.9 ms | 4.5 ms | 6.8 ms | n/a | 19.1 ms | 0.36 cores | kafka 60% · postgres 25% | 0.14 of 1 | kafka 0.60 | 6.03 | 136.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.6 ms | 2.2 ms | 4.0 ms | n/a | 9.2 ms | 0.20 cores | kafka 55% · postgres 10% | 0.16 of 1 | kafka 0.55 | 1.69 | 36.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

