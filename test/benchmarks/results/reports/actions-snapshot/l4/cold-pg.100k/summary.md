## cold-pg@1M

A million accounts on PostgreSQL, as cold-mongo.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: Intel(R) Xeon(R) 6975P-C, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

- `head`: build e3a4e937aa

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 1.3 ms | 3.0 ms | 5.1 ms | n/a | 54.8 ms | 0.28 cores | kafka 42% · postgres 15% | 0.10 of 1 | kafka 0.42 | 4.26 | 120.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.4 ms | 1.4 ms | 3.0 ms | n/a | 10.6 ms | 0.14 cores | kafka 37% · postgres 6% | 0.11 of 1 | kafka 0.37 | 1.15 | 35.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

