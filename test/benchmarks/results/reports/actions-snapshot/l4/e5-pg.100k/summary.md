## e5-pg@100k

E5 cluster of three nodes on PostgreSQL, as e5-mongo.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (110–116 s), then waited until the nodes were quiet again (4–8 s).

- `head`: build e3a4e937aa

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.3 ms | 14.1 ms | 36.4 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 0 | 4.8 ms | 1044.5 ms | 3129.3 ms | 0 | 150/s | - | forwardFailed local=15 · stateRebuilt ungated-ran=110 · tailRead gap=4,start=33 |
| e3-steady | 50.0/s | 50.0/s | 0 | 6.7 ms | 27.0 ms | 67.1 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 50.0/s | 1 | 9.9 ms | 667.6 ms | 2030.4 ms | 0 | 49/s | http-503-wow.node.closing 1 | forwardFailed local=12 · stateRebuilt ungated-ran=93 · tailRead gap=13,start=24,idle=5 · refused wow.node.closing=1 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 3.3 ms | 9.1 ms | 14.1 ms | n/a | 36.4 ms | 0.66 cores | kafka 58% · postgres 15% | 0.17 of 1 | server 0.66 | 9.30 | 161.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-handover | head | 150.0/s | 150.0/s | 13500 | 0 | 4.8 ms | 23.2 ms | 1044.5 ms | n/a | 3129.3 ms | - | kafka 57% · postgres 19% | 0.20 of 1 | - | - | - | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 6.7 ms | 17.8 ms | 27.0 ms | n/a | 67.1 ms | 0.73 cores | kafka 57% · postgres 16% | 0.09 of 1 | server 0.73 | 29.08 | 514.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-handover | head | 50.0/s | 50.0/s | 4499 | 1 | 9.9 ms | 41.7 ms | 667.6 ms | n/a | 2030.4 ms | - | kafka 55% · postgres 18% | 0.10 of 1 | - | - | - | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | http-503-wow.node.closing 1 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-pg@100k | e1-handover | hand-over | **tail read the store** | gap=4 |
| e5-pg@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +36.3% (±25.0%) |
| e5-pg@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 4.8 ms, p99 1044.5 ms |
| e5-pg@100k | e3-steady | steady | **unstable** | second half against first, beyond the default limits: p50 -32.4% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **tail read the store** | gap=13 |
| e5-pg@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +38.3% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 9.9 ms, p99 667.6 ms |

