## mixed-mongo@1k

Reads and writes together on MongoDB (E1 deposits 50%, E4 point reads 40%, E4 lists 10%): whether the query bulkheads keep the writes fast. Head against baseline.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) 6973P-C, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1588.5/s | 128 | 536.1/s | 34% |
| head | 3271.3/s | 128 | 1104.0/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1270.8/s | 1027.1/s | 20542 | 4874 | 2048.0 ms | 9961.5 ms | 15073.3 ms | n/a | 19791.9 ms | 1.59 cores | kafka 57% · mongo 71% | 0.79 of 1 | server 1.59 | 2.80 | 105.5 KB | E4-list:read-http-429 2318 · E1:http-429-wow.command.busy 2020 · E1:http-504-wow.wait.timeout 472 · E1:exception-ConnectException 31 · E4-state:exception-ConnectException 26 · E4-list:exception-ConnectException 7 |
| probe2-base | base | 953.1/s | 902.5/s | 18049 | 1012 | 395.3 ms | 3440.6 ms | 5177.3 ms | n/a | 6357.0 ms | 1.53 cores | kafka 56% · mongo 83% | 0.60 of 1 | server 1.53 | 3.24 | 140.3 KB | E4-list:read-http-429 1012 |
| probe3-base | base | 714.8/s | 703.0/s | 14059 | 237 | 63.0 ms | 864.3 ms | 1359.9 ms | n/a | 2023.4 ms | 1.55 cores | kafka 50% · mongo 86% | 0.48 of 1 | server 1.55 | 4.14 | 164.4 KB | E4-list:read-http-429 237 |
| probe4-base | base | 536.1/s | 532.7/s | 10654 | 67 | 9.0 ms | 247.8 ms | 782.3 ms | n/a | 1138.7 ms | 1.48 cores | kafka 51% · mongo 82% | 0.43 of 1 | server 1.48 | 5.29 | 174.3 KB | E4-list:read-http-429 67 |
| probe1-head | head | 2617.0/s | 1059.8/s | 21196 | 31144 | 12582.9 ms | 14745.6 ms | 15597.6 ms | n/a | 16103.4 ms | 1.39 cores | kafka 50% · mongo 60% | 0.96 of 1 | server 1.39 | 2.35 | 38.5 KB | generator-overload 29742 · E4-list:read-http-429 1402 |
| probe2-head | head | 1962.8/s | 1427.6/s | 28552 | 10704 | 11862.0 ms | 16515.1 ms | 17825.8 ms | n/a | 18481.2 ms | 1.60 cores | kafka 54% · mongo 63% | 0.94 of 1 | server 1.60 | 1.94 | 66.5 KB | generator-overload 8460 · E4-list:read-http-429 1724 · E1:exception-ConnectException 258 · E4-state:exception-ConnectException 208 · E4-list:exception-ConnectException 54 |
| probe3-head | head | 1472.1/s | 1371.8/s | 27437 | 2005 | 1441.8 ms | 3751.9 ms | 4522.0 ms | n/a | 14745.6 ms | 1.72 cores | kafka 53% · mongo 66% | 0.84 of 1 | server 1.72 | 2.12 | 102.6 KB | E4-list:read-http-429 2005 |
| probe4-head | head | 1104.0/s | 1082.1/s | 21642 | 438 | 158.7 ms | 1384.4 ms | 1998.8 ms | n/a | 2670.6 ms | 1.69 cores | kafka 53% · mongo 71% | 0.64 of 1 | server 1.69 | 2.70 | 113.8 KB | E4-list:read-http-429 438 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 536.1/s | 536.1/s | 21443 | 0 | 2.9 ms | 12.0 ms | 42.5 ms | n/a | 109.1 ms | 0.80 cores | kafka 50% · mongo 85% | 0.21 of 1 | mongo 0.85 | 4.00 | 167.9 KB | - |
| r1-head | head | 1104.0/s | 1104.0/s | 44160 | 0 | 5.0 ms | 113.2 ms | 290.8 ms | n/a | 614.4 ms | 1.37 cores | kafka 46% · mongo 92% | 0.41 of 1 | server 1.37 | 2.49 | 107.7 KB | - |
| r2-head | head | 1104.0/s | 1104.0/s | 44160 | 0 | 3.8 ms | 74.2 ms | 184.3 ms | n/a | 342.0 ms | 1.24 cores | kafka 46% · mongo 92% | 0.41 of 1 | server 1.24 | 2.37 | 109.4 KB | - |
| r2-base | base | 536.1/s | 536.1/s | 21443 | 0 | 2.9 ms | 19.6 ms | 104.4 ms | n/a | 436.2 ms | 0.72 cores | kafka 47% · mongo 82% | 0.20 of 1 | mongo 0.82 | 3.74 | 165.7 KB | - |
| r3-base | base | 536.1/s | 536.1/s | 21443 | 0 | 2.8 ms | 10.5 ms | 33.0 ms | n/a | 77.3 ms | 0.69 cores | kafka 44% · mongo 82% | 0.20 of 1 | mongo 0.82 | 3.63 | 168.4 KB | - |
| r3-head | head | 1104.0/s | 1104.0/s | 44160 | 0 | 4.2 ms | 72.7 ms | 216.1 ms | n/a | 491.5 ms | 1.33 cores | kafka 45% · mongo 89% | 0.42 of 1 | server 1.33 | 2.42 | 108.4 KB | - |
| e1-base | base | 536.1/s | 536.1/s | 21443 | 0 | 2.8 ms | 15.0 ms | 64.5 ms | n/a | 257.0 ms | 0.67 cores | kafka 46% · mongo 81% | 0.21 of 1 | mongo 0.81 | 3.61 | 166.0 KB | - |
| e1-head | head | 536.1/s | 536.1/s | 21443 | 0 | 1.6 ms | 4.9 ms | 20.0 ms | n/a | 86.5 ms | 0.54 cores | kafka 47% · mongo 63% | 0.19 of 1 | mongo 0.63 | 3.07 | 118.7 KB | - |
| e2-head | head | 536.1/s | 536.1/s | 21443 | 0 | 1.6 ms | 4.6 ms | 12.1 ms | n/a | 64.3 ms | 0.54 cores | kafka 50% · mongo 64% | 0.20 of 1 | mongo 0.64 | 3.13 | 118.0 KB | - |
| e2-base | base | 536.1/s | 536.1/s | 21443 | 0 | 2.9 ms | 16.2 ms | 55.3 ms | n/a | 145.4 ms | 0.72 cores | kafka 49% · mongo 81% | 0.23 of 1 | mongo 0.81 | 3.75 | 171.1 KB | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed | E4-list p50 | E4-list p99 | E4-list failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 4.0 ms | 56.6 ms | 0 | 1.1 ms | 22.7 ms | 0 | 1.5 ms | 21.6 ms | 0 |
| r1-head | head | 7.2 ms | 370.7 ms | 0 | 3.1 ms | 202.8 ms | 0 | 3.8 ms | 215.0 ms | 0 |
| r2-head | head | 5.4 ms | 217.1 ms | 0 | 2.2 ms | 120.8 ms | 0 | 2.8 ms | 135.2 ms | 0 |
| r2-base | base | 3.6 ms | 139.3 ms | 0 | 1.0 ms | 62.0 ms | 0 | 1.3 ms | 66.0 ms | 0 |
| r3-base | base | 3.6 ms | 43.5 ms | 0 | 1.0 ms | 15.7 ms | 0 | 1.2 ms | 17.9 ms | 0 |
| r3-head | head | 6.0 ms | 259.1 ms | 0 | 2.6 ms | 152.6 ms | 0 | 3.2 ms | 151.6 ms | 0 |
| e1-base | base | 3.5 ms | 89.1 ms | 0 | 1.0 ms | 35.3 ms | 0 | 1.2 ms | 39.2 ms | 0 |
| e1-head | head | 2.2 ms | 26.6 ms | 0 | 1.0 ms | 13.2 ms | 0 | 1.2 ms | 15.4 ms | 0 |
| e2-head | head | 2.2 ms | 15.9 ms | 0 | 1.0 ms | 7.4 ms | 0 | 1.2 ms | 8.1 ms | 0 |
| e2-base | base | 3.7 ms | 64.8 ms | 0 | 1.0 ms | 33.5 ms | 0 | 1.3 ms | 30.8 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2.059 | 1.696 | 9.455 | 6.844 | - | 5.634 | 0.622 | 0.641 |
| 2 | 2.059 | 1.317 | 3.791 | 1.765 | - | 0.784 | 0.632 | 0.660 |
| 3 | 2.059 | 1.477 | 6.927 | 6.543 | - | 6.358 | 0.667 | 0.644 |

Pairs at one rate for both arms, 536.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.574 | 0.328 | 0.310 | - | 0.337 | 0.853 | 0.715 |
| 2 | 1.000 | 0.555 | 0.283 | 0.219 | - | 0.442 | 0.835 | 0.690 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -56.7% (±25.0%) |
| mixed-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 5.0 ms, p99 290.8 ms |
| mixed-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -48.0% (±25.0%) |
| mixed-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 3.8 ms, p99 184.3 ms |
| mixed-mongo@1k | r2-base | steady | **p99 > 10× p50** | p50 2.9 ms, p99 104.4 ms |
| mixed-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -51.0% (±25.0%) |
| mixed-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 4.2 ms, p99 216.1 ms |
| mixed-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 2.8 ms, p99 64.5 ms |
| mixed-mongo@1k | e2-base | steady | **p99 > 10× p50** | p50 2.9 ms, p99 55.3 ms |

