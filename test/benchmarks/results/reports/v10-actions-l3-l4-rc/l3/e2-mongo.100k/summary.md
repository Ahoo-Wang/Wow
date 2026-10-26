## e2-mongo@100k

E2 read-your-write on MongoDB: head against baseline.

E2, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (40–120 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 987.3/s | 2048 | 789.8/s | 80% |
| head | 1496.4/s | 256 | 897.8/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 789.8/s | 789.8/s | 15796 | 0 | 8126.5 ms | 10682.4 ms | 12189.7 ms | n/a | 13107.2 ms | 0.93 cores | kafka 40% · mongo 140% | 0.91 of 1 | mongo 1.40 | 3.46 | 287.8 KB | - |
| probe1-head | head | 1197.1/s | 1069.9/s | 21398 | 2544 | 18874.4 ms | 22413.3 ms | 24510.5 ms | n/a | 24722.6 ms | 0.75 cores | kafka 49% · mongo 101% | 0.96 of 1 | mongo 1.01 | 2.11 | 123.6 KB | generator-overload 2544 |
| probe2-head | head | 897.8/s | 897.8/s | 17956 | 0 | 6258.7 ms | 8781.8 ms | 9437.2 ms | n/a | 9830.4 ms | 0.79 cores | kafka 40% · mongo 105% | 0.96 of 1 | mongo 1.05 | 2.49 | 183.2 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 789.8/s | 789.8/s | 31592 | 0 | 1490.9 ms | 4489.2 ms | 5701.6 ms | n/a | 6979.6 ms | 0.95 cores | kafka 38% · mongo 148% | 0.72 of 1 | mongo 1.48 | 3.55 | 306.6 KB | - |
| r1-head | head | 897.8/s | 897.8/s | 35912 | 0 | 28.4 ms | 1015.8 ms | 1556.5 ms | n/a | 1703.9 ms | 0.86 cores | kafka 45% · mongo 122% | 0.73 of 1 | mongo 1.22 | 2.82 | 191.4 KB | - |
| r2-head | head | 897.8/s | 897.8/s | 35912 | 0 | 25.7 ms | 1130.5 ms | 1581.1 ms | n/a | 1720.3 ms | 0.84 cores | kafka 43% · mongo 121% | 0.71 of 1 | mongo 1.21 | 2.77 | 192.6 KB | - |
| r2-base | base | 789.8/s | 789.8/s | 31592 | 0 | 2539.5 ms | 6455.3 ms | 7569.4 ms | n/a | 9371.6 ms | 1.02 cores | kafka 38% · mongo 143% | 0.71 of 1 | mongo 1.43 | 3.58 | 308.5 KB | - |
| r3-base | base | 789.8/s | 789.8/s | 31592 | 0 | 1876.0 ms | 3883.0 ms | 4653.1 ms | n/a | 6062.1 ms | 0.96 cores | kafka 39% · mongo 145% | 0.73 of 1 | mongo 1.45 | 3.53 | 305.2 KB | - |
| r3-head | head | 897.8/s | 897.8/s | 35912 | 0 | 20.6 ms | 856.1 ms | 1359.9 ms | n/a | 1581.1 ms | 0.84 cores | kafka 42% · mongo 123% | 0.68 of 1 | mongo 1.23 | 2.77 | 192.5 KB | - |
| e1-base | base | 789.8/s | 789.8/s | 31592 | 0 | 1531.9 ms | 3555.3 ms | 4423.7 ms | n/a | 5931.0 ms | 0.93 cores | kafka 40% · mongo 146% | 0.68 of 1 | mongo 1.46 | 3.52 | 303.3 KB | - |
| e1-head | head | 789.8/s | 789.8/s | 31592 | 0 | 12.7 ms | 75.8 ms | 138.2 ms | n/a | 259.1 ms | 0.81 cores | kafka 45% · mongo 119% | 0.57 of 1 | mongo 1.19 | 3.10 | 198.9 KB | - |
| e2-head | head | 789.8/s | 789.8/s | 31592 | 0 | 14.1 ms | 90.6 ms | 152.6 ms | n/a | 315.4 ms | 0.79 cores | kafka 42% · mongo 121% | 0.59 of 1 | mongo 1.21 | 3.07 | 194.3 KB | - |
| e2-base | base | 789.8/s | 789.8/s | 31592 | 0 | 2900.0 ms | 5472.3 ms | 6553.6 ms | n/a | 7995.4 ms | 0.94 cores | kafka 40% · mongo 144% | 0.74 of 1 | mongo 1.44 | 3.51 | 308.4 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.137 | 0.019 | 0.226 | 0.273 | - | 0.244 | 0.795 | 0.624 |
| 2 | 1.137 | 0.010 | 0.175 | 0.209 | - | 0.184 | 0.774 | 0.625 |
| 3 | 1.137 | 0.011 | 0.220 | 0.292 | - | 0.261 | 0.783 | 0.631 |

Pairs at one rate for both arms, 789.8/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.008 | 0.021 | 0.031 | - | 0.044 | 0.881 | 0.656 |
| 2 | 1.000 | 0.005 | 0.017 | 0.023 | - | 0.039 | 0.874 | 0.630 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e2-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -60.2% (±25.0%) |
| e2-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.0% (±25.0%) |
| e2-mongo@100k | r1-head | steady | **p99 > 10× p50** | p50 28.4 ms, p99 1556.5 ms |
| e2-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -52.4% (±25.0%) |
| e2-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 25.7 ms, p99 1581.1 ms |
| e2-mongo@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -69.8% (±25.0%) |
| e2-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -53.6% (±25.0%) |
| e2-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -40.7% (±25.0%) |
| e2-mongo@100k | r3-head | steady | **p99 > 10× p50** | p50 20.6 ms, p99 1359.9 ms |
| e2-mongo@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -53.4% (±25.0%) |
| e2-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 12.7 ms, p99 138.2 ms |
| e2-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 14.1 ms, p99 152.6 ms |
| e2-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -50.8% (±25.0%) |

