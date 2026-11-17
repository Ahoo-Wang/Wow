## e5-mongo@100k

E5 cluster of three nodes on MongoDB, requests round-robin: E1 and E3 steady, then with one node stopped and restarted mid-measurement (its partitions handed over and back).

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: Intel(R) Xeon(R) 6975P-C, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (85–93 s), then waited until the nodes were quiet again (4–4 s).

- `head`: build e3a4e937aa

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.0 ms | 13.7 ms | 34.3 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 0 | 4.4 ms | 66.0 ms | 1671.2 ms | 0 | 150/s | - | forwardFailed local=14 · stateRebuilt ungated-ran=76 · tailRead gap=16,start=26 |
| e3-steady | 50.0/s | 50.0/s | 0 | 7.1 ms | 26.9 ms | 59.8 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 49.6/s | 39 | 9.2 ms | 5701.6 ms | 9920.0 ms | 0 | 40/s | http-504-wow.wait.timeout 39 | stateRebuilt ungated-ran=70 · tailRead gap=21,start=25,idle=1 · waitTimedOut COMMITTED=39 · forwardFailed local=10 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 3.0 ms | 8.0 ms | 13.7 ms | n/a | 34.3 ms | 0.46 cores | kafka 45% · mongo 48% | 0.11 of 1 | mongo 0.48 | 9.23 | 234.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-handover | head | 150.0/s | 150.0/s | 13500 | 0 | 4.4 ms | 18.2 ms | 66.0 ms | n/a | 1671.2 ms | - | kafka 44% · mongo 55% | 0.12 of 1 | - | - | - | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 7.1 ms | 18.0 ms | 26.9 ms | n/a | 59.8 ms | 0.56 cores | kafka 44% · mongo 49% | 0.07 of 1 | server 0.56 | 29.75 | 737.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-handover | head | 50.0/s | 49.6/s | 4461 | 39 | 9.2 ms | 60.7 ms | 5701.6 ms | n/a | 9920.0 ms | - | kafka 44% · mongo 51% | 0.07 of 1 | - | - | - | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | http-504-wow.wait.timeout 39 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-mongo@100k | e1-handover | hand-over | **tail read the store** | gap=16 |
| e5-mongo@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +72.5% (±25.0%) |
| e5-mongo@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 4.4 ms, p99 66.0 ms |
| e5-mongo@100k | e3-handover | hand-over | **tail read the store** | gap=21 |
| e5-mongo@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +75.6% (±25.0%) |
| e5-mongo@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 9.2 ms, p99 5701.6 ms |

