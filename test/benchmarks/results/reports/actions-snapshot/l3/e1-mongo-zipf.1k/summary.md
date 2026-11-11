## e1-mongo-zipf@1k

E1 write on MongoDB, Zipf keys (hot accounts, lane and cache contention): head against baseline.

E1, zipf keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1686.4/s | 1024 | 569.2/s | 34% (**not sustained**) |
| head | 1721.0/s | 1024 | 580.8/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1349.1/s | 585.1/s | 11703 | 15279 | 1269.8 ms | 7897.1 ms | 9895.9 ms | n/a | 10223.6 ms | 0.99 cores | kafka 63% · mongo 51% | 0.45 of 1 | server 0.99 | 3.65 | 45.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | generator-overload 13839 · http-504-wow.wait.timeout 918 · http-429-wow.command.busy 522 |
| probe2-base | base | 1011.8/s | 624.3/s | 12486 | 7750 | 1212.4 ms | 6979.6 ms | 9699.3 ms | n/a | 10223.6 ms | 1.00 cores | kafka 65% · mongo 52% | 0.43 of 1 | server 1.00 | 3.47 | 64.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 6216 · http-504-wow.wait.timeout 924 · http-429-wow.command.busy 610 |
| probe3-base | base | 758.9/s | 540.4/s | 10807 | 4371 | 1130.5 ms | 9502.7 ms | 10223.6 ms | n/a | 10551.3 ms | 0.99 cores | kafka 65% · mongo 52% | 0.40 of 1 | server 0.99 | 4.01 | 77.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 3178 · http-504-wow.wait.timeout 1026 · http-429-wow.command.busy 167 |
| probe4-base | base | 569.2/s | 533.5/s | 10671 | 712 | 794.6 ms | 7307.3 ms | 10092.5 ms | n/a | 10503.2 ms | 0.99 cores | kafka 65% · mongo 54% | 0.39 of 1 | server 0.99 | 4.09 | 104.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | http-504-wow.wait.timeout 598 · http-429-wow.command.busy 114 |
| probe1-head | head | 1376.8/s | 570.1/s | 11402 | 16134 | 1343.5 ms | 8585.2 ms | 10158.1 ms | n/a | 10616.8 ms | 1.00 cores | kafka 64% · mongo 53% | 0.43 of 1 | server 1.00 | 3.79 | 43.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | generator-overload 14929 · http-504-wow.wait.timeout 847 · http-429-wow.command.busy 358 |
| probe2-head | head | 1032.6/s | 573.4/s | 11467 | 9184 | 1294.3 ms | 8060.9 ms | 9109.5 ms | n/a | 10130.5 ms | 0.99 cores | kafka 65% · mongo 52% | 0.47 of 1 | server 0.99 | 3.78 | 58.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | generator-overload 7863 · http-504-wow.wait.timeout 1000 · http-429-wow.command.busy 321 |
| probe3-head | head | 774.5/s | 557.0/s | 11141 | 4349 | 1286.1 ms | 7831.6 ms | 9240.6 ms | n/a | 10027.0 ms | 0.99 cores | kafka 63% · mongo 53% | 0.43 of 1 | server 0.99 | 3.84 | 77.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 3017 · http-504-wow.wait.timeout 976 · http-429-wow.command.busy 356 |
| probe4-head | head | 580.8/s | 525.0/s | 10500 | 1116 | 999.4 ms | 9502.7 ms | 9961.5 ms | n/a | 10289.2 ms | 1.00 cores | kafka 65% · mongo 55% | 0.37 of 1 | server 1.00 | 4.18 | 98.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | generator-overload 727 · http-504-wow.wait.timeout 354 · http-429-wow.command.busy 35 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 569.2/s | 569.2/s | 22767 | 0 | 86.0 ms | 622.6 ms | 4390.9 ms | n/a | 5435.2 ms | 1.00 cores | kafka 57% · mongo 71% | 0.23 of 1 | server 1.00 | 4.01 | 122.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r1-head | head | 580.8/s | 580.8/s | 23232 | 0 | 120.8 ms | 2162.7 ms | 6127.6 ms | n/a | 7110.7 ms | 1.00 cores | kafka 53% · mongo 70% | 0.22 of 1 | server 1.00 | 3.82 | 121.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r2-head | head | 580.8/s | 580.7/s | 23229 | 3 | 136.2 ms | 2883.6 ms | 6750.2 ms | n/a | 7897.1 ms | 1.00 cores | kafka 56% · mongo 68% | 0.23 of 1 | server 1.00 | 3.85 | 121.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | http-429-wow.command.busy 3 |
| r2-base | base | 569.2/s | 563.6/s | 22546 | 221 | 142.3 ms | 3211.3 ms | 6553.6 ms | n/a | 9568.3 ms | 1.00 cores | kafka 53% · mongo 67% | 0.22 of 1 | server 1.00 | 3.91 | 121.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | generator-overload 221 |
| r3-base | base | 569.2/s | 569.2/s | 22767 | 0 | 58.6 ms | 593.9 ms | 4620.3 ms | n/a | 5537.8 ms | 1.00 cores | kafka 53% · mongo 73% | 0.22 of 1 | server 1.00 | 3.96 | 124.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r3-head | head | 580.8/s | 580.8/s | 23232 | 0 | 139.3 ms | 3211.3 ms | 6651.9 ms | n/a | 7569.4 ms | 1.00 cores | kafka 56% · mongo 69% | 0.23 of 1 | server 1.00 | 3.87 | 120.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-base | base | 569.2/s | 569.2/s | 22767 | 0 | 122.9 ms | 1900.5 ms | 5898.2 ms | n/a | 7241.7 ms | 1.00 cores | kafka 51% · mongo 70% | 0.21 of 1 | server 1.00 | 3.87 | 119.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-head | head | 569.2/s | 569.2/s | 22767 | 0 | 199.7 ms | 4882.4 ms | 8126.5 ms | n/a | 8716.3 ms | 1.00 cores | kafka 56% · mongo 66% | 0.23 of 1 | server 1.00 | 3.89 | 118.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-head | head | 569.2/s | 569.2/s | 22767 | 0 | 140.3 ms | 3555.3 ms | 6979.6 ms | n/a | 8323.1 ms | 1.00 cores | kafka 54% · mongo 68% | 0.23 of 1 | server 1.00 | 3.90 | 121.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-base | base | 569.2/s | 569.2/s | 22767 | 0 | 75.3 ms | 540.7 ms | 4685.8 ms | n/a | 5931.0 ms | 1.00 cores | kafka 53% · mongo 71% | 0.22 of 1 | server 1.00 | 3.93 | 121.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.020 | 1.405 | 3.474 | 1.396 | - | 1.308 | 0.954 | 0.996 |
| 2 | 1.030 | 0.957 | 0.898 | 1.030 | - | 0.825 | 0.985 | 0.994 |
| 3 | 1.020 | 2.376 | 5.407 | 1.440 | - | 1.367 | 0.978 | 0.966 |

Pairs at one rate for both arms, 569.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.625 | 2.569 | 1.378 | - | 1.204 | 1.003 | 0.989 |
| 2 | 1.000 | 1.864 | 6.576 | 1.490 | - | 1.403 | 0.992 | 0.996 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-zipf@1k | r1-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -84.2% (±25.0%) |
| e1-mongo-zipf@1k | r1-base | steady | **p99 > 10× p50** | p50 86.0 ms, p99 4390.9 ms |
| e1-mongo-zipf@1k | r1-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -81.5% (±25.0%) |
| e1-mongo-zipf@1k | r1-head | steady | **p99 > 10× p50** | p50 120.8 ms, p99 6127.6 ms |
| e1-mongo-zipf@1k | r2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -74.5% (±25.0%) |
| e1-mongo-zipf@1k | r2-head | steady | **p99 > 10× p50** | p50 136.2 ms, p99 6750.2 ms |
| e1-mongo-zipf@1k | r2-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.8% (±25.0%) |
| e1-mongo-zipf@1k | r2-base | steady | **p99 > 10× p50** | p50 142.3 ms, p99 6553.6 ms |
| e1-mongo-zipf@1k | r3-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -85.3% (±25.0%) |
| e1-mongo-zipf@1k | r3-base | steady | **p99 > 10× p50** | p50 58.6 ms, p99 4620.3 ms |
| e1-mongo-zipf@1k | r3-head | steady | **tail read the store** | overflow=1 |
| e1-mongo-zipf@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -79.4% (±25.0%) |
| e1-mongo-zipf@1k | r3-head | steady | **p99 > 10× p50** | p50 139.3 ms, p99 6651.9 ms |
| e1-mongo-zipf@1k | e1-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.4% (±25.0%) |
| e1-mongo-zipf@1k | e1-base | steady | **p99 > 10× p50** | p50 122.9 ms, p99 5898.2 ms |
| e1-mongo-zipf@1k | e1-head | steady | **tail read the store** | overflow=1 |
| e1-mongo-zipf@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.1% (±25.0%) |
| e1-mongo-zipf@1k | e1-head | steady | **p99 > 10× p50** | p50 199.7 ms, p99 8126.5 ms |
| e1-mongo-zipf@1k | e2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.6% (±25.0%) |
| e1-mongo-zipf@1k | e2-head | steady | **p99 > 10× p50** | p50 140.3 ms, p99 6979.6 ms |
| e1-mongo-zipf@1k | e2-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -79.0% (±25.0%) |
| e1-mongo-zipf@1k | e2-base | steady | **p99 > 10× p50** | p50 75.3 ms, p99 4685.8 ms |

