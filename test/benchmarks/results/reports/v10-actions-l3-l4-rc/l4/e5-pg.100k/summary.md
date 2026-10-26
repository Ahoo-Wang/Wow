## e5-pg@100k

E5 cluster of three nodes on PostgreSQL, as e5-mongo.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (68–78 s), then waited until the nodes were quiet again (3–4 s).

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 2.6 ms | 12.7 ms | 116.7 ms | 0 | 150/s | - | tailRead idle=9 |
| e1-handover | 150.0/s | 150.0/s | 0 | 2.9 ms | 71.7 ms | 757.8 ms | 0 | 150/s | - | forwardFailed local=8 · tailRead gap=6,idle=8,start=34 |
| e3-steady | 50.0/s | 50.0/s | 0 | 6.2 ms | 36.6 ms | 238.2 ms | 0 | 50/s | - | tailRead idle=6 |
| e3-handover | 50.0/s | 50.0/s | 0 | 7.2 ms | 90.1 ms | 1204.5 ms | 0 | 50/s | - | forwardFailed local=12 · tailRead gap=20,idle=9,start=20 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 2.6 ms | 6.0 ms | 12.7 ms | n/a | 116.7 ms | 0.48 cores | kafka 41% · postgres 12% | 0.12 of 1 | server 0.48 | 6.76 | 161.2 KB | - |
| e1-handover | head | 150.0/s | 150.0/s | 13500 | 0 | 2.9 ms | 16.6 ms | 71.7 ms | n/a | 757.8 ms | - | kafka 40% · postgres 14% | 0.12 of 1 | - | - | - | - |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 6.2 ms | 13.9 ms | 36.6 ms | n/a | 238.2 ms | 0.57 cores | kafka 42% · postgres 13% | 0.08 of 1 | server 0.57 | 22.41 | 451.5 KB | - |
| e3-handover | head | 50.0/s | 50.0/s | 4500 | 0 | 7.2 ms | 26.1 ms | 90.1 ms | n/a | 1204.5 ms | - | kafka 40% · postgres 14% | 0.08 of 1 | - | - | - | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-pg@100k | e1-handover | hand-over | **tail read the store** | gap=6 |
| e5-pg@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +71.1% (±25.0%) |
| e5-pg@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 2.9 ms, p99 71.7 ms |
| e5-pg@100k | e3-handover | hand-over | **tail read the store** | gap=20 |
| e5-pg@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +35.7% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 7.2 ms, p99 90.1 ms |

