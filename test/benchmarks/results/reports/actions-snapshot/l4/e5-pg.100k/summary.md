## e5-pg@100k

E5 cluster of three nodes on PostgreSQL, as e5-mongo.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (118–131 s), then waited until the nodes were quiet again (4–7 s).

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.1 ms | 13.9 ms | 54.5 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 0 | 4.8 ms | 487.4 ms | 1785.9 ms | 0 | 150/s | - | forwardFailed local=23 · tailRead gap=5,idle=2,start=36 |
| e3-steady | 50.0/s | 50.0/s | 0 | 6.7 ms | 23.4 ms | 50.9 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 50.0/s | 0 | 10.6 ms | 1065.0 ms | 4170.1 ms | 0 | 50/s | - | forwardFailed local=12 · tailRead gap=5,start=22,idle=1 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 3.1 ms | 8.6 ms | 13.9 ms | n/a | 54.5 ms | 0.64 cores | kafka 57% · postgres 16% | 0.16 of 1 | server 0.64 | 9.20 | 179.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-handover | head | 150.0/s | 150.0/s | 13500 | 0 | 4.8 ms | 29.3 ms | 487.4 ms | n/a | 1785.9 ms | - | kafka 58% · postgres 21% | 0.18 of 1 | - | - | - | - | datasets 8 of 8 · default 2–3 of 5, lowered 0, pressed 1 s | - |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 6.7 ms | 16.1 ms | 23.4 ms | n/a | 50.9 ms | 0.71 cores | kafka 57% · postgres 16% | 0.10 of 1 | server 0.71 | 28.73 | 561.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-handover | head | 50.0/s | 50.0/s | 4500 | 0 | 10.6 ms | 45.1 ms | 1065.0 ms | n/a | 4170.1 ms | - | kafka 56% · postgres 20% | 0.10 of 1 | - | - | - | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-pg@100k | e1-handover | hand-over | **tail read the store** | gap=5 |
| e5-pg@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +90.0% (±25.0%) |
| e5-pg@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 4.8 ms, p99 487.4 ms |
| e5-pg@100k | e3-handover | hand-over | **tail read the store** | gap=5 |
| e5-pg@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +53.6% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 10.6 ms, p99 1065.0 ms |

