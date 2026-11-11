## e5-mongo@100k

E5 cluster of three nodes on MongoDB, requests round-robin: E1 and E3 steady, then with one node stopped and restarted mid-measurement (its partitions handed over and back).

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (152–160 s), then waited until the nodes were quiet again (4–8 s).

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 4.7 ms | 31.5 ms | 81.4 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 1 | 11.5 ms | 2359.3 ms | 5079.0 ms | 0 | 149/s | exception-IOException 1 | forwardFailed local=30 · tailRead gap=12,start=26,idle=4 |
| e3-steady | 50.0/s | 50.0/s | 0 | 10.5 ms | 49.7 ms | 107.5 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 49.7/s | 30 | 22.4 ms | 6389.8 ms | 9712.7 ms | 0 | 44/s | http-504-wow.wait.timeout 29 · http-503-wow.node.closing 1 | forwardFailed local=27 · tailRead gap=22,start=24,idle=8 · waitTimedOut COMMITTED=29 · refused wow.node.closing=1 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 4.7 ms | 18.2 ms | 31.5 ms | n/a | 81.4 ms | 0.80 cores | kafka 53% · mongo 65% | 0.15 of 1 | server 0.80 | 13.15 | 257.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-handover | head | 150.0/s | 150.0/s | 13499 | 1 | 11.5 ms | 132.1 ms | 2359.3 ms | n/a | 5079.0 ms | - | kafka 52% · mongo 73% | 0.18 of 1 | - | - | - | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | exception-IOException 1 |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 10.5 ms | 33.5 ms | 49.7 ms | n/a | 107.5 ms | 0.87 cores | kafka 52% · mongo 63% | 0.10 of 1 | server 0.87 | 40.30 | 759.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-handover | head | 50.0/s | 49.7/s | 4470 | 30 | 22.4 ms | 1286.1 ms | 6389.8 ms | n/a | 9712.7 ms | - | kafka 50% · mongo 69% | 0.11 of 1 | - | - | - | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | http-504-wow.wait.timeout 29 · http-503-wow.node.closing 1 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-mongo@100k | e1-steady | steady | **unstable** | second half against first, beyond the default limits: p50 -39.7% (±25.0%) |
| e5-mongo@100k | e1-handover | hand-over | **tail read the store** | gap=12 |
| e5-mongo@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 11.5 ms, p99 2359.3 ms |
| e5-mongo@100k | e3-steady | steady | **unstable** | second half against first, beyond the default limits: p50 -50.2% (±25.0%) |
| e5-mongo@100k | e3-handover | hand-over | **tail read the store** | gap=22 |
| e5-mongo@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 22.4 ms, p99 6389.8 ms |

