## mixed-mongo@100k

Reads and writes together on MongoDB (E1 deposits 50%, E4 point reads 40%, E4 lists 10%): whether the query bulkheads keep the writes fast. Head against baseline.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (59–177 s), then waited until the nodes were quiet again (4–6 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 843.1/s | 64 | 505.9/s | 60% |
| head | 1910.3/s | 256 | 859.6/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 674.5/s | 627.5/s | 12551 | 939 | 2056.2 ms | 5275.6 ms | 6193.2 ms | n/a | 7045.1 ms | 1.04 cores | kafka 38% · mongo 133% | 0.85 of 1 | mongo 1.33 | 4.39 | 185.9 KB | E4-list:read-http-429 939 |
| probe2-base | base | 505.9/s | 505.9/s | 10117 | 1 | 24.7 ms | 393.2 ms | 704.5 ms | n/a | 1130.5 ms | 0.99 cores | kafka 46% · mongo 120% | 0.64 of 1 | mongo 1.20 | 5.25 | 199.4 KB | E4-list:read-http-429 1 |
| probe1-head | head | 1528.2/s | 824.3/s | 16485 | 14079 | 17432.6 ms | 19791.9 ms | 19791.9 ms | n/a | 19998.5 ms | 0.84 cores | kafka 58% · mongo 97% | 0.95 of 1 | mongo 0.97 | 2.89 | 48.7 KB | generator-overload 6556 · E1:exception-ConnectException 3768 · E4-state:exception-ConnectException 3002 · E4-list:exception-ConnectException 739 · E4-list:read-http-429 14 |
| probe2-head | head | 1146.2/s | 1134.3/s | 22685 | 239 | 8257.5 ms | 11010.0 ms | 11599.9 ms | n/a | 11993.1 ms | 0.89 cores | kafka 54% · mongo 102% | 0.94 of 1 | mongo 1.02 | 2.17 | 91.0 KB | E4-list:read-http-429 239 |
| probe3-head | head | 859.6/s | 859.2/s | 17184 | 8 | 2506.8 ms | 4849.7 ms | 4882.4 ms | n/a | 4948.0 ms | 0.96 cores | kafka 49% · mongo 106% | 0.93 of 1 | mongo 1.06 | 2.91 | 110.7 KB | E4-list:read-http-429 8 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 505.9/s | 505.9/s | 20236 | 0 | 5.1 ms | 65.3 ms | 203.8 ms | n/a | 464.9 ms | 0.76 cores | kafka 50% · mongo 124% | 0.31 of 1 | mongo 1.24 | 4.94 | 206.4 KB | - |
| r1-head | head | 859.6/s | 859.6/s | 34384 | 0 | 4.2 ms | 60.9 ms | 153.6 ms | n/a | 256.0 ms | 0.81 cores | kafka 54% · mongo 116% | 0.52 of 1 | mongo 1.16 | 2.92 | 106.3 KB | - |
| r2-head | head | 859.6/s | 859.6/s | 34384 | 0 | 3.3 ms | 32.5 ms | 93.7 ms | n/a | 272.4 ms | 0.76 cores | kafka 54% · mongo 116% | 0.48 of 1 | mongo 1.16 | 2.86 | 108.3 KB | - |
| r2-base | base | 505.9/s | 505.1/s | 20205 | 31 | 7.6 ms | 122.4 ms | 286.7 ms | n/a | 725.0 ms | 0.82 cores | kafka 49% · mongo 129% | 0.36 of 1 | mongo 1.29 | 5.14 | 203.3 KB | E4-list:read-http-429 31 |
| r3-base | base | 505.9/s | 505.8/s | 20233 | 3 | 5.9 ms | 110.1 ms | 299.0 ms | n/a | 634.9 ms | 0.77 cores | kafka 52% · mongo 127% | 0.31 of 1 | mongo 1.27 | 5.05 | 204.8 KB | E4-list:read-http-429 3 |
| r3-head | head | 859.6/s | 859.6/s | 34384 | 0 | 3.5 ms | 30.6 ms | 86.5 ms | n/a | 214.0 ms | 0.79 cores | kafka 54% · mongo 118% | 0.47 of 1 | mongo 1.18 | 2.91 | 108.7 KB | - |
| e1-base | base | 505.9/s | 505.9/s | 20236 | 0 | 5.8 ms | 101.4 ms | 284.7 ms | n/a | 737.3 ms | 0.77 cores | kafka 50% · mongo 126% | 0.31 of 1 | mongo 1.26 | 5.01 | 203.6 KB | - |
| e1-head | head | 505.9/s | 505.9/s | 20236 | 0 | 2.4 ms | 17.3 ms | 67.6 ms | n/a | 159.7 ms | 0.61 cores | kafka 60% · mongo 96% | 0.33 of 1 | mongo 0.96 | 4.29 | 116.7 KB | - |
| e2-head | head | 505.9/s | 505.9/s | 20236 | 0 | 2.0 ms | 11.0 ms | 52.7 ms | n/a | 164.9 ms | 0.57 cores | kafka 57% · mongo 91% | 0.27 of 1 | mongo 0.91 | 4.04 | 116.6 KB | - |
| e2-base | base | 505.9/s | 505.9/s | 20236 | 0 | 6.6 ms | 98.8 ms | 235.5 ms | n/a | 909.3 ms | 0.80 cores | kafka 49% · mongo 128% | 0.33 of 1 | mongo 1.28 | 5.08 | 204.2 KB | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed | E4-list p50 | E4-list p99 | E4-list failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 8.9 ms | 278.5 ms | 0 | 1.9 ms | 68.1 ms | 0 | 2.6 ms | 104.4 ms | 0 |
| r1-head | head | 5.9 ms | 189.4 ms | 0 | 2.8 ms | 110.6 ms | 0 | 3.7 ms | 113.7 ms | 0 |
| r2-head | head | 4.5 ms | 114.2 ms | 0 | 2.1 ms | 58.6 ms | 0 | 3.0 ms | 62.0 ms | 0 |
| r2-base | base | 14.3 ms | 370.7 ms | 0 | 3.0 ms | 93.2 ms | 0 | 4.0 ms | 179.2 ms | 31 |
| r3-base | base | 10.7 ms | 364.5 ms | 0 | 2.4 ms | 181.2 ms | 0 | 3.3 ms | 237.6 ms | 3 |
| r3-head | head | 4.9 ms | 111.1 ms | 0 | 2.2 ms | 52.5 ms | 0 | 3.1 ms | 57.3 ms | 0 |
| e1-base | base | 10.8 ms | 378.9 ms | 0 | 2.3 ms | 116.2 ms | 0 | 3.1 ms | 102.4 ms | 0 |
| e1-head | head | 3.2 ms | 79.9 ms | 0 | 1.7 ms | 48.4 ms | 0 | 2.4 ms | 54.3 ms | 0 |
| e2-head | head | 2.4 ms | 69.1 ms | 0 | 1.4 ms | 29.2 ms | 0 | 1.9 ms | 31.0 ms | 0 |
| e2-base | base | 12.6 ms | 311.3 ms | 0 | 2.6 ms | 79.9 ms | 0 | 3.6 ms | 110.1 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.699 | 0.824 | 0.933 | 0.754 | - | 0.551 | 0.592 | 0.515 |
| 2 | 1.702 | 0.433 | 0.266 | 0.327 | - | 0.376 | 0.556 | 0.533 |
| 3 | 1.699 | 0.586 | 0.278 | 0.289 | - | 0.337 | 0.577 | 0.531 |

Pairs at one rate for both arms, 505.9/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.420 | 0.170 | 0.237 | - | 0.217 | 0.857 | 0.573 |
| 2 | 1.000 | 0.307 | 0.111 | 0.224 | - | 0.181 | 0.795 | 0.571 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.7% (±25.0%) |
| mixed-mongo@100k | r1-base | steady | **p99 > 10× p50** | p50 5.1 ms, p99 203.8 ms |
| mixed-mongo@100k | r1-head | steady | **p99 > 10× p50** | p50 4.2 ms, p99 153.6 ms |
| mixed-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 3.3 ms, p99 93.7 ms |
| mixed-mongo@100k | r2-base | steady | **p99 > 10× p50** | p50 7.6 ms, p99 286.7 ms |
| mixed-mongo@100k | r3-base | steady | **p99 > 10× p50** | p50 5.9 ms, p99 299.0 ms |
| mixed-mongo@100k | r3-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 86.5 ms |
| mixed-mongo@100k | e1-base | steady | **p99 > 10× p50** | p50 5.8 ms, p99 284.7 ms |
| mixed-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 2.4 ms, p99 67.6 ms |
| mixed-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 2.0 ms, p99 52.7 ms |
| mixed-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -34.2% (±25.0%) |
| mixed-mongo@100k | e2-base | steady | **p99 > 10× p50** | p50 6.6 ms, p99 235.5 ms |

