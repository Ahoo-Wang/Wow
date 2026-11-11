## mixed-pg@100k

Reads and writes together on PostgreSQL, as mixed-mongo.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (67–77 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2187.8/s | 64 | 984.5/s | 45% |
| head | 2031.4/s | 64 | 914.1/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1750.2/s | 1661.7/s | 33234 | 1770 | 103.4 ms | 876.5 ms | 1278.0 ms | n/a | 1515.5 ms | 1.03 cores | kafka 41% · postgres 58% | 0.70 of 2 | server 1.03 | 1.21 | 75.8 KB | 1770 | datasets 8 of 8 · default 1–4 of 5, lowered 1, pressed 16 s | - |
| probe2-base | base | 1312.7/s | 1292.5/s | 25849 | 405 | 26.9 ms | 294.9 ms | 464.9 ms | n/a | 675.8 ms | 0.96 cores | kafka 45% · postgres 59% | 0.58 of 2 | server 0.96 | 1.54 | 72.7 KB | 405 | datasets 8 of 8 · default 1–5 of 5, lowered 2, pressed 8 s | - |
| probe3-base | base | 984.5/s | 984.5/s | 19690 | 0 | 5.1 ms | 34.6 ms | 59.4 ms | n/a | 88.1 ms | 0.90 cores | kafka 43% · postgres 54% | 0.48 of 2 | server 0.90 | 1.89 | 73.1 KB | - | datasets 8 of 8 · default 3–4 of 5, lowered 0, pressed 2 s | - |
| probe1-head | head | 1625.1/s | 1513.5/s | 30269 | 2233 | 352.3 ms | 1450.0 ms | 1990.7 ms | n/a | 2588.7 ms | 0.99 cores | kafka 51% · postgres 51% | 0.59 of 2 | server 0.99 | 1.33 | 76.9 KB | 2233 | datasets 8 of 8 · default 1 of 5, lowered 0, pressed 21 s | - |
| probe2-head | head | 1218.8/s | 1203.8/s | 24075 | 301 | 20.9 ms | 176.1 ms | 274.4 ms | n/a | 391.2 ms | 0.95 cores | kafka 46% · postgres 59% | 0.53 of 2 | server 0.95 | 1.66 | 74.3 KB | 301 | datasets 8 of 8 · default 1–5 of 5, lowered 2, pressed 9 s | - |
| probe3-head | head | 914.1/s | 914.1/s | 18282 | 0 | 3.7 ms | 17.4 ms | 36.9 ms | n/a | 157.7 ms | 0.92 cores | kafka 49% · postgres 44% | 0.48 of 2 | server 0.92 | 2.02 | 76.4 KB | - | datasets 8 of 8 · default 3–4 of 5, lowered 0, pressed 0 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 984.5/s | 984.5/s | 39380 | 0 | 3.2 ms | 19.1 ms | 53.8 ms | n/a | 145.4 ms | 0.77 cores | kafka 52% · postgres 49% | 0.35 of 2 | server 0.77 | 1.82 | 72.6 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 2 s | - |
| r1-head | head | 914.1/s | 914.1/s | 36564 | 0 | 2.4 ms | 11.8 ms | 29.4 ms | n/a | 162.8 ms | 0.73 cores | kafka 54% · postgres 45% | 0.32 of 2 | server 0.73 | 1.88 | 74.9 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |
| r2-head | head | 914.1/s | 914.1/s | 36564 | 0 | 2.3 ms | 11.3 ms | 38.1 ms | n/a | 162.8 ms | 0.74 cores | kafka 54% · postgres 44% | 0.31 of 2 | server 0.74 | 1.89 | 75.0 KB | - | datasets 8 of 8 · default 4–5 of 5, lowered 0, pressed 1 s | - |
| r2-base | base | 984.5/s | 984.5/s | 39380 | 0 | 3.3 ms | 17.8 ms | 64.3 ms | n/a | 274.4 ms | 0.77 cores | kafka 50% · postgres 49% | 0.38 of 2 | server 0.77 | 1.80 | 72.6 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 2 s | - |
| r3-base | base | 984.5/s | 984.5/s | 39380 | 0 | 2.7 ms | 13.9 ms | 46.1 ms | n/a | 164.9 ms | 0.76 cores | kafka 50% · postgres 48% | 0.35 of 2 | server 0.76 | 1.77 | 72.6 KB | - | datasets 8 of 8 · default 4–5 of 5, lowered 0, pressed 1 s | - |
| r3-head | head | 914.1/s | 914.1/s | 36563 | 1 | 2.0 ms | 11.9 ms | 43.8 ms | n/a | 196.6 ms | 0.73 cores | kafka 51% · postgres 47% | 0.31 of 2 | server 0.73 | 1.86 | 74.8 KB | 1 | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |
| e1-base | base | 914.1/s | 914.1/s | 36564 | 0 | 2.8 ms | 14.9 ms | 33.3 ms | n/a | 124.4 ms | 0.74 cores | kafka 53% · postgres 49% | 0.36 of 2 | server 0.74 | 1.93 | 72.7 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |
| e1-head | head | 914.1/s | 914.1/s | 36564 | 0 | 2.9 ms | 18.0 ms | 72.2 ms | n/a | 190.5 ms | 0.77 cores | kafka 52% · postgres 45% | 0.35 of 2 | server 0.77 | 1.91 | 74.4 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 1 s | - |
| e2-head | head | 914.1/s | 914.1/s | 36564 | 0 | 3.0 ms | 18.6 ms | 48.9 ms | n/a | 177.2 ms | 0.76 cores | kafka 52% · postgres 49% | 0.37 of 2 | server 0.76 | 1.93 | 75.5 KB | - | datasets 8 of 8 · default 3–5 of 5, lowered 0, pressed 0 s | - |
| e2-base | base | 914.1/s | 914.1/s | 36564 | 0 | 2.8 ms | 14.0 ms | 29.1 ms | n/a | 150.5 ms | 0.74 cores | kafka 53% · postgres 49% | 0.32 of 2 | server 0.74 | 1.92 | 73.5 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E4-list p50 | E4-list p99 | E4-list failed | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 3.8 ms | 36.4 ms | 0 | 3.9 ms | 63.5 ms | 0 | 2.3 ms | 30.0 ms | 0 |
| r1-head | head | 2.9 ms | 20.6 ms | 0 | 2.9 ms | 37.9 ms | 0 | 1.7 ms | 17.7 ms | 0 |
| r2-head | head | 2.8 ms | 27.8 ms | 0 | 2.8 ms | 48.9 ms | 0 | 1.6 ms | 21.2 ms | 0 |
| r2-base | base | 4.0 ms | 45.3 ms | 0 | 4.1 ms | 90.6 ms | 0 | 2.4 ms | 30.2 ms | 0 |
| r3-base | base | 3.4 ms | 31.0 ms | 0 | 3.4 ms | 66.6 ms | 0 | 2.0 ms | 22.8 ms | 0 |
| r3-head | head | 2.5 ms | 33.5 ms | 1 | 2.5 ms | 62.7 ms | 0 | 1.4 ms | 24.3 ms | 0 |
| e1-base | base | 3.5 ms | 22.7 ms | 0 | 3.5 ms | 41.0 ms | 0 | 2.1 ms | 19.6 ms | 0 |
| e1-head | head | 3.6 ms | 56.6 ms | 0 | 3.6 ms | 91.6 ms | 0 | 2.2 ms | 39.9 ms | 0 |
| e2-head | head | 3.6 ms | 29.8 ms | 0 | 3.6 ms | 64.5 ms | 0 | 2.2 ms | 19.7 ms | 0 |
| e2-base | base | 3.6 ms | 23.0 ms | 0 | 3.5 ms | 36.1 ms | 0 | 2.1 ms | 17.8 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.928 | 0.747 | 0.617 | 0.548 | - | 1.120 | 1.033 | 1.031 |
| 2 | 0.928 | 0.699 | 0.637 | 0.594 | - | 0.593 | 1.049 | 1.033 |
| 3 | 0.928 | 0.754 | 0.857 | 0.950 | - | 1.193 | 1.055 | 1.030 |

Pairs at one rate for both arms, 914.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.040 | 1.210 | 2.169 | - | 1.531 | 0.989 | 1.024 |
| 2 | 1.000 | 1.051 | 1.324 | 1.683 | - | 1.177 | 1.008 | 1.027 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -27.3% (±25.0%) |
| mixed-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 3.2 ms, p99 53.8 ms |
| mixed-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +41.8% (±25.0%) |
| mixed-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +25.2% (±25.0%) |
| mixed-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -59.8% (±25.0%) |
| mixed-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 3.3 ms, p99 64.3 ms |
| mixed-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -39.4% (±25.0%) |
| mixed-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +53.0% (±25.0%) |
| mixed-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +31.3% (±25.0%) |
| mixed-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +105.4% (±25.0%) |
| mixed-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 2.9 ms, p99 72.2 ms |
| mixed-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +39.4% (±25.0%) |
| mixed-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.1% (±25.0%) |

