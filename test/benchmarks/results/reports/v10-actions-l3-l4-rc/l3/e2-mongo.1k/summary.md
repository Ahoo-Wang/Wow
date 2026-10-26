## e2-mongo@1k

E2 read-your-write on MongoDB: head against baseline.

E2, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1314.9/s | 1024 | 591.7/s | 45% |
| head | 1717.8/s | 256 | 1030.7/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1051.9/s | 948.4/s | 18967 | 2070 | 14549.0 ms | 17956.9 ms | 19398.7 ms | n/a | 20185.1 ms | 1.44 cores | kafka 52% · mongo 85% | 0.91 of 1 | server 1.44 | 2.97 | 164.5 KB | http-429-wow.command.busy 1967 · http-504-wow.wait.timeout 103 |
| probe2-base | base | 788.9/s | 764.1/s | 15283 | 495 | 5111.8 ms | 9830.4 ms | 10944.5 ms | n/a | 11599.9 ms | 1.54 cores | kafka 47% · mongo 86% | 0.83 of 1 | server 1.54 | 3.76 | 238.1 KB | http-504-wow.wait.timeout 318 · http-429-wow.command.busy 177 |
| probe3-base | base | 591.7/s | 591.6/s | 11833 | 0 | 1761.3 ms | 4685.8 ms | 6160.4 ms | n/a | 8388.6 ms | 1.47 cores | kafka 48% · mongo 91% | 0.70 of 1 | server 1.47 | 4.83 | 283.8 KB | - |
| probe1-head | head | 1374.2/s | 842.2/s | 16844 | 10640 | 18874.4 ms | 26083.3 ms | 28180.5 ms | n/a | 28834.2 ms | 1.39 cores | kafka 50% · mongo 67% | 0.95 of 1 | server 1.39 | 3.04 | 83.6 KB | generator-overload 8580 · exception-ConnectException 2060 |
| probe2-head | head | 1030.7/s | 1030.7/s | 20614 | 0 | 15401.0 ms | 19791.9 ms | 21889.0 ms | n/a | 23199.7 ms | 1.47 cores | kafka 52% · mongo 71% | 0.94 of 1 | server 1.47 | 2.61 | 146.9 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 591.7/s | 591.7/s | 23667 | 0 | 84.0 ms | 770.0 ms | 1196.0 ms | n/a | 2056.2 ms | 1.23 cores | kafka 37% · mongo 112% | 0.42 of 1 | server 1.23 | 4.59 | 293.7 KB | - |
| r1-head | head | 1030.7/s | 907.1/s | 36283 | 4945 | 18743.3 ms | 21758.0 ms | 23068.7 ms | n/a | 25630.7 ms | 1.23 cores | kafka 45% · mongo 80% | 0.95 of 1 | server 1.23 | 2.74 | 156.4 KB | generator-overload 4945 |
| r2-head | head | 1030.7/s | 1030.7/s | 41228 | 0 | 14352.4 ms | 15663.1 ms | 15990.8 ms | n/a | 19136.5 ms | 1.20 cores | kafka 43% · mongo 88% | 0.95 of 1 | server 1.20 | 2.43 | 179.1 KB | - |
| r2-base | base | 591.7/s | 591.7/s | 23667 | 0 | 235.5 ms | 1044.5 ms | 1425.4 ms | n/a | 2277.4 ms | 1.42 cores | kafka 40% · mongo 106% | 0.44 of 1 | server 1.42 | 4.87 | 286.4 KB | - |
| r3-base | base | 591.7/s | 591.7/s | 23667 | 0 | 94.2 ms | 569.3 ms | 925.7 ms | n/a | 1949.7 ms | 1.30 cores | - | 0.42 of 1 | server 1.30 | 2.19 | 298.0 KB | - |
| r3-head | head | 1030.7/s | 1030.7/s | 41228 | 0 | 1105.9 ms | 4620.3 ms | 5177.3 ms | n/a | 5898.2 ms | 1.20 cores | kafka 44% · mongo 96% | 0.85 of 1 | server 1.20 | 2.52 | 190.9 KB | - |
| e1-base | base | 591.7/s | 591.7/s | 23667 | 0 | 84.5 ms | 1007.6 ms | 1605.6 ms | n/a | 2113.5 ms | 1.29 cores | kafka 42% · mongo 109% | 0.43 of 1 | server 1.29 | 4.74 | 299.8 KB | - |
| e1-head | head | 591.7/s | 591.7/s | 23667 | 0 | 18.0 ms | 130.0 ms | 266.2 ms | n/a | 522.2 ms | 1.25 cores | kafka 49% · mongo 92% | 0.43 of 1 | server 1.25 | 4.49 | 203.8 KB | - |
| e2-head | head | 591.7/s | 591.7/s | 23667 | 0 | 18.9 ms | 216.1 ms | 497.7 ms | n/a | 692.2 ms | 1.32 cores | kafka 46% · mongo 91% | 0.44 of 1 | server 1.32 | 4.54 | 204.0 KB | - |
| e2-base | base | 591.7/s | 591.7/s | 23667 | 0 | 356.4 ms | 1007.6 ms | 1294.3 ms | n/a | 1835.0 ms | 1.44 cores | kafka 42% · mongo 106% | 0.46 of 1 | server 1.44 | 4.93 | 284.1 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.533 | 223.222 | 28.255 | 19.288 | - | 12.465 | 0.597 | 0.532 |
| 2 | 1.742 | 60.939 | 14.996 | 11.218 | - | 8.403 | 0.500 | 0.625 |
| 3 | 1.742 | 11.739 | 8.115 | 5.593 | - | 3.025 | 1.151 | 0.641 |

Pairs at one rate for both arms, 591.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.214 | 0.129 | 0.166 | - | 0.247 | 0.947 | 0.680 |
| 2 | 1.000 | 0.053 | 0.214 | 0.384 | - | 0.377 | 0.920 | 0.718 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e2-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -93.0% (±25.0%) |
| e2-mongo@1k | r1-base | steady | **p99 > 10× p50** | p50 84.0 ms, p99 1196.0 ms |
| e2-mongo@1k | r1-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| e2-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: throughput +31.6% (±5.0%) |
| e2-mongo@1k | r1-head | steady | **error spike** | 4945 of 41228 failed: generator-overload 4945 |
| e2-mongo@1k | r1-head | steady | **throughput dip** | 4 s below half the rate (lowest 1/s) |
| e2-mongo@1k | r2-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| e2-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.0% (±25.0%) |
| e2-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -75.4% (±25.0%) |
| e2-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -96.7% (±25.0%) |
| e2-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -86.7% (±25.0%) |
| e2-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 84.5 ms, p99 1605.6 ms |
| e2-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -68.6% (±25.0%) |
| e2-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 18.0 ms, p99 266.2 ms |
| e2-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -63.8% (±25.0%) |
| e2-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 18.9 ms, p99 497.7 ms |
| e2-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -41.6% (±25.0%) |

