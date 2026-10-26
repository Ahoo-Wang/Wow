## cold-pg@1M

A million accounts on PostgreSQL, as cold-mongo.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 1.4 ms | 4.4 ms | 16.8 ms | n/a | 78.3 ms | 0.22 cores | kafka 41% · postgres 16% | 0.12 of 1 | kafka 0.41 | 3.98 | 113.2 KB | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.4 ms | 1.3 ms | 2.5 ms | n/a | 4.2 ms | 0.11 cores | kafka 34% · postgres 6% | 0.15 of 1 | kafka 0.34 | 1.01 | 33.2 KB | - |

