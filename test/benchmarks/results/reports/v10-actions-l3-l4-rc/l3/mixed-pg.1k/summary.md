## mixed-pg@1k

Reads and writes together on PostgreSQL, as mixed-mongo.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1738.4/s | 32 | 586.7/s | 34% |
| head | 2240.5/s | 64 | 756.2/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1390.7/s | 843.6/s | 16873 | 10941 | 16580.6 ms | 20316.2 ms | 22020.1 ms | n/a | 22413.3 ms | 1.60 cores | kafka 72% · postgres 39% | 0.91 of 1 | server 1.60 | 3.20 | 50.9 KB | generator-overload 4532 · E1:exception-ConnectException 2742 · E4-state:exception-ConnectException 2179 · E4-list:read-http-429 944 · E4-list:exception-ConnectException 544 |
| probe2-base | base | 1043.0/s | 968.6/s | 19372 | 1488 | 6324.2 ms | 8847.4 ms | 10354.7 ms | n/a | 12058.6 ms | 1.62 cores | kafka 76% · postgres 42% | 0.91 of 1 | server 1.62 | 2.89 | 76.8 KB | E4-list:read-http-429 1488 |
| probe3-base | base | 782.3/s | 759.5/s | 15190 | 456 | 138.2 ms | 2572.3 ms | 4554.8 ms | n/a | 7405.6 ms | 1.58 cores | kafka 75% · postgres 45% | 0.78 of 1 | server 1.58 | 3.66 | 95.0 KB | E4-list:read-http-429 456 |
| probe4-base | base | 586.7/s | 585.2/s | 11704 | 30 | 5.8 ms | 366.6 ms | 1122.3 ms | n/a | 1622.0 ms | 1.59 cores | kafka 73% · postgres 37% | 0.62 of 1 | server 1.59 | 4.59 | 88.8 KB | E4-list:read-http-429 30 |
| probe1-head | head | 1792.4/s | 832.8/s | 16655 | 19192 | 18350.1 ms | 20578.3 ms | 20578.3 ms | n/a | 20629.5 ms | 1.46 cores | kafka 79% · postgres 36% | 0.94 of 1 | server 1.46 | 3.14 | 26.6 KB | generator-overload 13612 · E1:exception-ConnectException 2733 · E4-state:exception-ConnectException 2184 · E4-list:exception-ConnectException 538 · E4-list:read-http-429 125 |
| probe2-head | head | 1344.3/s | 1065.8/s | 21317 | 5569 | 16121.9 ms | 21233.7 ms | 21495.8 ms | n/a | 21758.0 ms | 1.50 cores | kafka 76% · postgres 43% | 0.93 of 1 | server 1.50 | 2.52 | 49.5 KB | generator-overload 2339 · E1:exception-ConnectException 1399 · E4-state:exception-ConnectException 1111 · E4-list:read-http-429 433 · E4-list:exception-ConnectException 287 |
| probe3-head | head | 1008.2/s | 980.3/s | 19605 | 559 | 6815.7 ms | 8159.2 ms | 9961.5 ms | n/a | 10551.3 ms | 1.58 cores | kafka 78% · postgres 40% | 0.91 of 1 | server 1.58 | 2.83 | 71.0 KB | E4-list:read-http-429 559 |
| probe4-head | head | 756.2/s | 740.5/s | 14810 | 314 | 270.3 ms | 2637.8 ms | 3129.3 ms | n/a | 3588.1 ms | 1.65 cores | kafka 76% · postgres 40% | 0.87 of 1 | server 1.65 | 3.79 | 84.9 KB | E4-list:read-http-429 314 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 586.7/s | 586.7/s | 23468 | 0 | 1.4 ms | 5.8 ms | 28.0 ms | n/a | 147.5 ms | 0.93 cores | kafka 64% · postgres 31% | 0.30 of 1 | server 0.93 | 3.20 | 79.8 KB | - |
| r1-head | head | 756.2/s | 756.2/s | 30248 | 0 | 3.0 ms | 26.9 ms | 92.2 ms | n/a | 243.7 ms | 1.51 cores | kafka 59% · postgres 46% | 0.41 of 1 | server 1.51 | 3.39 | 71.6 KB | - |
| r2-head | head | 756.2/s | 756.2/s | 30248 | 0 | 2.7 ms | 33.0 ms | 89.6 ms | n/a | 309.2 ms | 1.47 cores | kafka 62% · postgres 41% | 0.43 of 1 | server 1.47 | 3.30 | 71.1 KB | - |
| r2-base | base | 586.7/s | 586.7/s | 23468 | 0 | 1.6 ms | 7.4 ms | 24.2 ms | n/a | 74.2 ms | 1.11 cores | kafka 61% · postgres 33% | 0.34 of 1 | server 1.11 | 3.50 | 81.0 KB | - |
| r3-base | base | 586.7/s | 586.7/s | 23468 | 0 | 1.7 ms | 6.0 ms | 16.1 ms | n/a | 40.2 ms | 1.11 cores | kafka 62% · postgres 33% | 0.32 of 1 | server 1.11 | 3.52 | 79.9 KB | - |
| r3-head | head | 756.2/s | 756.2/s | 30248 | 0 | 3.0 ms | 42.2 ms | 116.7 ms | n/a | 325.6 ms | 1.52 cores | kafka 61% · postgres 42% | 0.44 of 1 | server 1.52 | 3.37 | 71.7 KB | - |
| e1-base | base | 586.7/s | 586.7/s | 23467 | 1 | 1.6 ms | 7.1 ms | 40.2 ms | n/a | 248.8 ms | 1.10 cores | kafka 64% · postgres 33% | 0.32 of 1 | server 1.10 | 3.54 | 80.3 KB | E4-list:read-http-429 1 |
| e1-head | head | 586.7/s | 586.7/s | 23468 | 0 | 1.6 ms | 7.7 ms | 39.9 ms | n/a | 179.2 ms | 1.13 cores | kafka 60% · postgres 32% | 0.33 of 1 | server 1.13 | 3.50 | 71.3 KB | - |
| e2-head | head | 586.7/s | 586.7/s | 23468 | 0 | 1.6 ms | 5.7 ms | 17.2 ms | n/a | 71.7 ms | 1.14 cores | kafka 65% · postgres 32% | 0.31 of 1 | server 1.14 | 3.59 | 70.8 KB | - |
| e2-base | base | 586.7/s | 586.7/s | 23468 | 0 | 1.8 ms | 9.6 ms | 30.7 ms | n/a | 73.7 ms | 1.13 cores | kafka 63% · postgres 34% | 0.35 of 1 | server 1.13 | 3.59 | 80.1 KB | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed | E4-list p50 | E4-list p99 | E4-list failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1.6 ms | 33.8 ms | 0 | 1.0 ms | 21.9 ms | 0 | 1.8 ms | 32.6 ms | 0 |
| r1-head | head | 3.3 ms | 119.3 ms | 0 | 2.0 ms | 51.2 ms | 0 | 4.4 ms | 59.1 ms | 0 |
| r2-head | head | 3.1 ms | 109.6 ms | 0 | 1.8 ms | 60.7 ms | 0 | 3.3 ms | 85.5 ms | 0 |
| r2-base | base | 1.9 ms | 27.0 ms | 0 | 1.2 ms | 19.2 ms | 0 | 2.2 ms | 25.5 ms | 0 |
| r3-base | base | 1.9 ms | 17.5 ms | 0 | 1.2 ms | 12.5 ms | 0 | 2.3 ms | 19.1 ms | 0 |
| r3-head | head | 3.5 ms | 144.4 ms | 0 | 2.0 ms | 83.5 ms | 0 | 3.6 ms | 96.8 ms | 0 |
| e1-base | base | 1.9 ms | 43.0 ms | 0 | 1.2 ms | 30.2 ms | 0 | 2.2 ms | 42.2 ms | 1 |
| e1-head | head | 1.9 ms | 55.8 ms | 0 | 1.2 ms | 22.7 ms | 0 | 2.1 ms | 33.0 ms | 0 |
| e2-head | head | 1.9 ms | 21.6 ms | 0 | 1.2 ms | 12.3 ms | 0 | 2.1 ms | 17.8 ms | 0 |
| e2-base | base | 2.0 ms | 32.3 ms | 0 | 1.3 ms | 26.9 ms | 0 | 2.5 ms | 36.6 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.289 | 2.068 | 4.667 | 3.288 | - | 1.653 | 1.057 | 0.898 |
| 2 | 1.289 | 1.641 | 4.449 | 3.704 | - | 4.166 | 0.945 | 0.878 |
| 3 | 1.289 | 1.771 | 6.985 | 7.267 | - | 8.102 | 0.957 | 0.898 |

Pairs at one rate for both arms, 586.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.005 | 1.076 | 0.994 | - | 0.720 | 0.988 | 0.887 |
| 2 | 1.000 | 0.919 | 0.590 | 0.558 | - | 0.972 | 1.001 | 0.884 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -37.6% (±25.0%) |
| mixed-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 3.0 ms, p99 92.2 ms |
| mixed-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -46.6% (±25.0%) |
| mixed-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 2.7 ms, p99 89.6 ms |
| mixed-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -31.5% (±25.0%) |
| mixed-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -33.6% (±25.0%) |
| mixed-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.6% (±25.0%) |
| mixed-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 3.0 ms, p99 116.7 ms |
| mixed-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -29.1% (±25.0%) |
| mixed-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.0% (±25.0%) |
| mixed-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.4% (±25.0%) |
| mixed-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -36.0% (±25.0%) |
| mixed-pg@1k | probe2-base | overload (probe) | **tail read the store** | gap=1 |

