## e1-mongo-zipf@1k

E1 write on MongoDB, Zipf keys (hot accounts, lane and cache contention): head against baseline.

E1, zipf keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 441.0/s | 128 | 352.8/s | 80% |
| head | 3029.3/s | 512 | 1363.2/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 352.8/s | 352.3/s | 7046 | 10 | 47.6 ms | 8355.8 ms | 9830.4 ms | n/a | 10009.3 ms | 1.40 cores | kafka 53% · mongo 88% | 0.37 of 1 | server 1.40 | 7.99 | 275.4 KB | http-504-wow.wait.timeout 10 |
| probe1-head | head | 2423.4/s | 870.6/s | 17413 | 31055 | 12910.6 ms | 16449.5 ms | 17039.4 ms | n/a | 17553.5 ms | 1.34 cores | kafka 67% · mongo 57% | 0.97 of 1 | server 1.34 | 2.97 | 32.8 KB | generator-overload 27497 · exception-ConnectException 3501 · http-429-wow.command.busy 57 |
| probe2-head | head | 1817.6/s | 1093.3/s | 21867 | 14485 | 14876.7 ms | 19005.4 ms | 21102.6 ms | n/a | 22544.4 ms | 1.48 cores | kafka 67% · mongo 57% | 0.96 of 1 | server 1.48 | 2.48 | 53.5 KB | generator-overload 10772 · exception-ConnectException 3713 |
| probe3-head | head | 1363.2/s | 1363.2/s | 27263 | 0 | 7897.1 ms | 10616.8 ms | 11730.9 ms | n/a | 12386.3 ms | 1.53 cores | kafka 66% · mongo 65% | 0.95 of 1 | server 1.53 | 2.09 | 89.5 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 352.8/s | 352.8/s | 14112 | 0 | 6.0 ms | 1744.9 ms | 6553.6 ms | n/a | 8257.5 ms | 0.81 cores | kafka 53% · mongo 89% | 0.20 of 1 | mongo 0.89 | 6.33 | 261.3 KB | - |
| r1-head | head | 1363.2/s | 1363.2/s | 54527 | 0 | 31.2 ms | 294.9 ms | 471.0 ms | n/a | 794.6 ms | 1.25 cores | kafka 48% · mongo 88% | 0.60 of 1 | server 1.25 | 1.92 | 104.3 KB | - |
| r2-head | head | 1363.2/s | 1363.2/s | 54527 | 0 | 26.2 ms | 1458.2 ms | 2736.1 ms | n/a | 3342.3 ms | 1.33 cores | kafka 49% · mongo 93% | 0.67 of 1 | server 1.33 | 2.02 | 109.5 KB | - |
| r2-base | base | 352.8/s | 352.8/s | 14112 | 0 | 7.7 ms | 786.4 ms | 2785.3 ms | n/a | 3326.0 ms | 0.82 cores | kafka 50% · mongo 88% | 0.21 of 1 | mongo 0.88 | 6.25 | 255.0 KB | - |
| r3-base | base | 352.8/s | 352.8/s | 14112 | 0 | 6.0 ms | 544.8 ms | 4882.4 ms | n/a | 5852.4 ms | 0.74 cores | kafka 49% · mongo 87% | 0.18 of 1 | mongo 0.87 | 5.94 | 258.2 KB | - |
| r3-head | head | 1363.2/s | 1363.2/s | 54527 | 0 | 31.4 ms | 540.7 ms | 1171.5 ms | n/a | 2015.2 ms | 1.23 cores | kafka 50% · mongo 90% | 0.63 of 1 | server 1.23 | 1.94 | 104.8 KB | - |
| e1-base | base | 352.8/s | 352.8/s | 14112 | 0 | 6.6 ms | 2834.4 ms | 7536.6 ms | n/a | 8192.0 ms | 0.71 cores | kafka 51% · mongo 87% | 0.20 of 1 | mongo 0.87 | 5.93 | 259.3 KB | - |
| e1-head | head | 352.8/s | 352.8/s | 14112 | 0 | 2.3 ms | 87.6 ms | 183.3 ms | n/a | 294.9 ms | 0.50 cores | kafka 53% · mongo 61% | 0.20 of 1 | mongo 0.61 | 4.65 | 143.1 KB | - |
| e2-head | head | 352.8/s | 352.8/s | 14112 | 0 | 2.8 ms | 156.7 ms | 288.8 ms | n/a | 428.0 ms | 0.44 cores | kafka 51% · mongo 58% | 0.17 of 1 | mongo 0.58 | 4.34 | 136.5 KB | - |
| e2-base | base | 352.8/s | 352.8/s | 14112 | 0 | 20.1 ms | 1327.1 ms | 4718.6 ms | n/a | 6422.5 ms | 0.72 cores | kafka 46% · mongo 84% | 0.18 of 1 | mongo 0.84 | 5.74 | 252.7 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 3.864 | 5.248 | 0.169 | 0.072 | - | 0.096 | 0.303 | 0.399 |
| 2 | 3.864 | 3.417 | 1.854 | 0.982 | - | 1.005 | 0.324 | 0.429 |
| 3 | 3.864 | 5.270 | 0.992 | 0.240 | - | 0.344 | 0.326 | 0.406 |

Pairs at one rate for both arms, 352.8/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.354 | 0.031 | 0.024 | - | 0.036 | 0.783 | 0.552 |
| 2 | 1.000 | 0.140 | 0.118 | 0.061 | - | 0.067 | 0.756 | 0.540 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-zipf@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.2% (±25.0%) |
| e1-mongo-zipf@1k | r1-base | steady | **p99 > 10× p50** | p50 6.0 ms, p99 6553.6 ms |
| e1-mongo-zipf@1k | r1-head | steady | **tail read the store** | overflow=5 |
| e1-mongo-zipf@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -33.3% (±25.0%) |
| e1-mongo-zipf@1k | r1-head | steady | **p99 > 10× p50** | p50 31.2 ms, p99 471.0 ms |
| e1-mongo-zipf@1k | r2-head | steady | **tail read the store** | overflow=4 |
| e1-mongo-zipf@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -71.4% (±25.0%) |
| e1-mongo-zipf@1k | r2-head | steady | **p99 > 10× p50** | p50 26.2 ms, p99 2736.1 ms |
| e1-mongo-zipf@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -69.9% (±25.0%) |
| e1-mongo-zipf@1k | r2-base | steady | **p99 > 10× p50** | p50 7.7 ms, p99 2785.3 ms |
| e1-mongo-zipf@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.2% (±25.0%) |
| e1-mongo-zipf@1k | r3-base | steady | **p99 > 10× p50** | p50 6.0 ms, p99 4882.4 ms |
| e1-mongo-zipf@1k | r3-head | steady | **tail read the store** | overflow=4 |
| e1-mongo-zipf@1k | r3-head | steady | **p99 > 10× p50** | p50 31.4 ms, p99 1171.5 ms |
| e1-mongo-zipf@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -86.8% (±25.0%) |
| e1-mongo-zipf@1k | e1-base | steady | **p99 > 10× p50** | p50 6.6 ms, p99 7536.6 ms |
| e1-mongo-zipf@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -34.6% (±25.0%) |
| e1-mongo-zipf@1k | e1-head | steady | **p99 > 10× p50** | p50 2.3 ms, p99 183.3 ms |
| e1-mongo-zipf@1k | e2-head | steady | **p99 > 10× p50** | p50 2.8 ms, p99 288.8 ms |
| e1-mongo-zipf@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +451.0% (±25.0%) |
| e1-mongo-zipf@1k | e2-base | steady | **p99 > 10× p50** | p50 20.1 ms, p99 4718.6 ms |
| e1-mongo-zipf@1k | probe2-head | overload (probe) | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | probe3-head | overload (probe) | **tail read the store** | overflow=1 |

