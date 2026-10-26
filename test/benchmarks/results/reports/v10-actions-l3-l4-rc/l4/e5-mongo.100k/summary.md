## e5-mongo@100k

E5 cluster of three nodes on MongoDB, requests round-robin: E1 and E3 steady, then with one node stopped and restarted mid-measurement (its partitions handed over and back).

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (110–118 s), then waited until the nodes were quiet again (4–4 s).

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.7 ms | 52.7 ms | 204.8 ms | 0 | 150/s | - | tailRead idle=6 |
| e1-handover | 150.0/s | 150.0/s | 0 | 6.1 ms | 1400.8 ms | 4358.1 ms | 0 | 150/s | - | forwardFailed local=16 · tailRead gap=17,idle=20,start=35 |
| e3-steady | 50.0/s | 50.0/s | 0 | 8.2 ms | 147.5 ms | 449.1 ms | 0 | 50/s | - | tailRead idle=14 |
| e3-handover | 50.0/s | 50.0/s | 4 | 13.4 ms | 3375.1 ms | 6715.6 ms | 0 | 48/s | http-504-wow.wait.timeout 3 · exception-IOException 1 | forwardFailed local=5 · tailRead gap=20,idle=23,start=30 · waitTimedOut COMMITTED=3 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 3.7 ms | 12.4 ms | 52.7 ms | n/a | 204.8 ms | 0.54 cores | kafka 57% · mongo 64% | 0.15 of 1 | mongo 0.64 | 11.64 | 234.4 KB | - |
| e1-handover | head | 150.0/s | 150.0/s | 13500 | 0 | 6.1 ms | 123.9 ms | 1400.8 ms | n/a | 4358.1 ms | - | kafka 54% · mongo 72% | 0.21 of 1 | - | - | - | - |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 8.2 ms | 38.7 ms | 147.5 ms | n/a | 449.1 ms | 0.66 cores | kafka 52% · mongo 61% | 0.09 of 1 | server 0.66 | 35.80 | 680.4 KB | - |
| e3-handover | head | 50.0/s | 50.0/s | 4496 | 4 | 13.4 ms | 297.0 ms | 3375.1 ms | n/a | 6715.6 ms | - | kafka 52% · mongo 66% | 0.11 of 1 | - | - | - | http-504-wow.wait.timeout 3 · exception-IOException 1 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-mongo@100k | e1-steady | steady | **p99 > 10× p50** | p50 3.7 ms, p99 52.7 ms |
| e5-mongo@100k | e1-handover | hand-over | **tail read the store** | gap=17 |
| e5-mongo@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +159.5% (±25.0%) |
| e5-mongo@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 6.1 ms, p99 1400.8 ms |
| e5-mongo@100k | e3-steady | steady | **p99 > 10× p50** | p50 8.2 ms, p99 147.5 ms |
| e5-mongo@100k | e3-handover | hand-over | **tail read the store** | gap=20 |
| e5-mongo@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +68.2% (±25.0%) |
| e5-mongo@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 13.4 ms, p99 3375.1 ms |

