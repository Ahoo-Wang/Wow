## e1-mongo-zipf@100k

E1 write on MongoDB, Zipf keys (hot accounts, lane and cache contention): head against baseline.

E1, zipf keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (58–172 s), then waited until the nodes were quiet again (4–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 532.5/s | 512 | 426.0/s | 80% |
| head | 2122.5/s | 512 | 1273.5/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 426.0/s | 426.0/s | 8520 | 0 | 40.7 ms | 6160.4 ms | 6750.2 ms | n/a | 7130.6 ms | 0.82 cores | kafka 50% · mongo 139% | 0.56 of 1 | mongo 1.39 | 6.36 | 273.5 KB | - |
| probe1-head | head | 1698.0/s | 857.8/s | 17156 | 16804 | 17563.6 ms | 20578.3 ms | 20691.3 ms | n/a | 20691.3 ms | 0.68 cores | kafka 59% · mongo 99% | 0.96 of 1 | mongo 0.99 | 2.64 | 44.0 KB | generator-overload 10851 · exception-ConnectException 5953 |
| probe2-head | head | 1273.5/s | 1273.5/s | 25470 | 0 | 10485.8 ms | 14090.2 ms | 15007.7 ms | n/a | 15663.1 ms | 0.88 cores | kafka 51% · mongo 109% | 0.94 of 1 | mongo 1.09 | 1.95 | 88.2 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 426.0/s | 426.0/s | 17040 | 0 | 12.4 ms | 417.8 ms | 1335.3 ms | n/a | 1884.2 ms | 0.79 cores | kafka 46% · mongo 138% | 0.29 of 1 | mongo 1.38 | 6.18 | 262.4 KB | - |
| r1-head | head | 1273.5/s | 1257.8/s | 50313 | 627 | 11206.7 ms | 14483.5 ms | 14680.1 ms | n/a | 14876.7 ms | 0.88 cores | kafka 58% · mongo 110% | 0.95 of 1 | mongo 1.10 | 2.04 | 103.6 KB | generator-overload 627 |
| r2-head | head | 1273.5/s | 1267.3/s | 50690 | 250 | 11730.9 ms | 13631.5 ms | 17825.8 ms | n/a | 18087.9 ms | 0.87 cores | kafka 53% · mongo 113% | 0.94 of 1 | mongo 1.13 | 2.00 | 100.7 KB | exception-ConnectException 250 |
| r2-base | base | 426.0/s | 426.0/s | 17040 | 0 | 13.1 ms | 368.6 ms | 901.1 ms | n/a | 1261.6 ms | 0.78 cores | kafka 49% · mongo 138% | 0.30 of 1 | mongo 1.38 | 6.23 | 264.9 KB | - |
| r3-base | base | 426.0/s | 426.0/s | 17040 | 0 | 12.2 ms | 292.9 ms | 782.3 ms | n/a | 1490.9 ms | 0.78 cores | kafka 49% · mongo 140% | 0.29 of 1 | mongo 1.40 | 6.28 | 265.3 KB | - |
| r3-head | head | 1273.5/s | 1273.5/s | 50940 | 0 | 4423.7 ms | 8028.2 ms | 8257.5 ms | n/a | 8355.8 ms | 0.86 cores | kafka 55% · mongo 114% | 0.94 of 1 | mongo 1.14 | 2.01 | 104.7 KB | - |
| e1-base | base | 426.0/s | 426.0/s | 17040 | 0 | 14.5 ms | 499.7 ms | 1646.6 ms | n/a | 2277.4 ms | 0.79 cores | kafka 47% · mongo 140% | 0.30 of 1 | mongo 1.40 | 6.23 | 267.1 KB | - |
| e1-head | head | 426.0/s | 426.0/s | 17040 | 0 | 2.5 ms | 11.6 ms | 39.7 ms | n/a | 105.0 ms | 0.54 cores | kafka 58% · mongo 106% | 0.27 of 1 | mongo 1.06 | 5.12 | 146.6 KB | - |
| e2-head | head | 426.0/s | 426.0/s | 17040 | 0 | 2.7 ms | 15.1 ms | 42.8 ms | n/a | 143.4 ms | 0.55 cores | kafka 59% · mongo 105% | 0.27 of 1 | mongo 1.05 | 5.14 | 145.7 KB | - |
| e2-base | base | 426.0/s | 426.0/s | 17040 | 0 | 17.2 ms | 602.1 ms | 3145.7 ms | n/a | 4060.0 ms | 0.81 cores | kafka 47% · mongo 143% | 0.30 of 1 | mongo 1.43 | 6.35 | 263.8 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2.953 | 902.671 | 34.667 | 10.994 | - | 7.896 | 0.330 | 0.395 |
| 2 | 2.975 | 894.195 | 36.978 | 19.782 | - | 14.338 | 0.321 | 0.380 |
| 3 | 2.989 | 363.819 | 27.413 | 10.555 | - | 5.604 | 0.319 | 0.395 |

Pairs at one rate for both arms, 426.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.175 | 0.023 | 0.024 | - | 0.046 | 0.822 | 0.549 |
| 2 | 1.000 | 0.159 | 0.025 | 0.014 | - | 0.035 | 0.809 | 0.552 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-zipf@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -30.8% (±25.0%) |
| e1-mongo-zipf@100k | r1-base | steady | **p99 > 10× p50** | p50 12.4 ms, p99 1335.3 ms |
| e1-mongo-zipf@100k | r1-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r1-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| e1-mongo-zipf@100k | r1-head | steady | **error spike** | 627 of 50940 failed: generator-overload 627 |
| e1-mongo-zipf@100k | r2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@100k | r2-head | steady | **load generator saturated** | 0.94 of 1 cores: the run measured the generator |
| e1-mongo-zipf@100k | r2-base | steady | **p99 > 10× p50** | p50 13.1 ms, p99 901.1 ms |
| e1-mongo-zipf@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -43.0% (±25.0%) |
| e1-mongo-zipf@100k | r3-base | steady | **p99 > 10× p50** | p50 12.2 ms, p99 782.3 ms |
| e1-mongo-zipf@100k | r3-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r3-head | steady | **load generator saturated** | 0.94 of 1 cores: the run measured the generator |
| e1-mongo-zipf@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -57.3% (±25.0%) |
| e1-mongo-zipf@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -56.7% (±25.0%) |
| e1-mongo-zipf@100k | e1-base | steady | **p99 > 10× p50** | p50 14.5 ms, p99 1646.6 ms |
| e1-mongo-zipf@100k | e2-base | steady | **p99 > 10× p50** | p50 17.2 ms, p99 3145.7 ms |

