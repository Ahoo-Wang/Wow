## mixed-pg@100k

Reads and writes together on PostgreSQL, as mixed-mongo.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (52–85 s), then waited until the nodes were quiet again (4–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1911.1/s | 64 | 860.0/s | 45% |
| head | 2403.6/s | 64 | 1442.2/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1528.9/s | 1079.0/s | 21579 | 8999 | 15138.8 ms | 19660.8 ms | 21102.6 ms | n/a | 21694.1 ms | 1.01 cores | kafka 61% · postgres 59% | 0.95 of 1 | server 1.01 | 2.06 | 52.6 KB | generator-overload 5181 · E1:exception-ConnectException 1766 · E4-state:exception-ConnectException 1413 · E4-list:exception-ConnectException 358 · E4-list:read-http-429 281 |
| probe2-base | base | 1146.7/s | 1108.9/s | 22178 | 756 | 5963.8 ms | 6750.2 ms | 8585.2 ms | n/a | 8978.4 ms | 1.01 cores | kafka 54% · postgres 68% | 0.93 of 1 | server 1.01 | 2.02 | 87.3 KB | E4-list:read-http-429 756 |
| probe3-base | base | 860.0/s | 854.9/s | 17098 | 102 | 7.8 ms | 2211.8 ms | 2424.8 ms | n/a | 2490.4 ms | 0.96 cores | kafka 51% · postgres 59% | 0.85 of 1 | server 0.96 | 2.42 | 89.1 KB | E4-list:read-http-429 102 |
| probe1-head | head | 1922.9/s | 1015.5/s | 20309 | 18149 | 15007.7 ms | 19005.4 ms | 19136.5 ms | n/a | 19158.3 ms | 0.79 cores | kafka 62% · postgres 40% | 0.97 of 1 | load generator 0.97 | 1.78 | 28.0 KB | generator-overload 15045 · E1:exception-ConnectException 1371 · E4-state:exception-ConnectException 1103 · E4-list:read-http-429 374 · E4-list:exception-ConnectException 256 |
| probe2-head | head | 1442.2/s | 1430.6/s | 28612 | 232 | 11337.7 ms | 15007.7 ms | 15138.8 ms | n/a | 15388.9 ms | 0.96 cores | kafka 59% · postgres 50% | 0.95 of 1 | server 0.96 | 1.43 | 55.4 KB | E4-list:read-http-429 232 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 860.0/s | 860.0/s | 34400 | 0 | 2.1 ms | 9.9 ms | 29.1 ms | n/a | 100.4 ms | 0.82 cores | kafka 59% · postgres 59% | 0.44 of 1 | server 0.82 | 2.31 | 84.8 KB | - |
| r1-head | head | 1442.2/s | 1404.3/s | 56172 | 1515 | 11927.6 ms | 13959.2 ms | 17301.5 ms | n/a | 17994.4 ms | 0.81 cores | kafka 53% · postgres 62% | 0.95 of 1 | load generator 0.95 | 1.40 | 67.2 KB | generator-overload 1080 · E4-list:read-http-429 435 |
| r2-head | head | 1442.2/s | 1424.1/s | 56963 | 724 | 10551.3 ms | 11730.9 ms | 15269.9 ms | n/a | 15466.5 ms | 0.82 cores | kafka 55% · postgres 62% | 0.96 of 1 | load generator 0.96 | 1.40 | 70.8 KB | E4-list:read-http-429 724 |
| r2-base | base | 860.0/s | 860.0/s | 34400 | 0 | 2.1 ms | 8.4 ms | 20.5 ms | n/a | 106.5 ms | 0.81 cores | kafka 58% · postgres 58% | 0.44 of 1 | server 0.81 | 2.29 | 85.3 KB | - |
| r3-base | base | 860.0/s | 860.0/s | 34400 | 0 | 2.0 ms | 9.1 ms | 27.3 ms | n/a | 93.7 ms | 0.80 cores | kafka 58% · postgres 57% | 0.43 of 1 | server 0.80 | 2.26 | 85.9 KB | - |
| r3-head | head | 1442.2/s | 1413.8/s | 56550 | 1137 | 12124.2 ms | 13303.8 ms | 17301.5 ms | n/a | 18463.3 ms | 0.83 cores | kafka 53% · postgres 60% | 0.96 of 1 | load generator 0.96 | 1.39 | 67.1 KB | E4-list:read-http-429 832 · generator-overload 127 · E1:exception-ConnectException 88 · E4-state:exception-ConnectException 74 · E4-list:exception-ConnectException 16 |
| e1-base | base | 860.0/s | 860.0/s | 34400 | 0 | 2.1 ms | 7.5 ms | 18.0 ms | n/a | 43.8 ms | 0.84 cores | kafka 54% · postgres 58% | 0.44 of 1 | server 0.84 | 2.28 | 85.8 KB | - |
| e1-head | head | 860.0/s | 860.0/s | 34400 | 0 | 1.5 ms | 5.2 ms | 16.9 ms | n/a | 120.8 ms | 0.72 cores | kafka 61% · postgres 46% | 0.42 of 1 | server 0.72 | 2.09 | 67.8 KB | - |
| e2-head | head | 860.0/s | 860.0/s | 34400 | 0 | 1.5 ms | 5.2 ms | 20.1 ms | n/a | 131.1 ms | 0.74 cores | kafka 60% · postgres 46% | 0.43 of 1 | server 0.74 | 2.09 | 68.4 KB | - |
| e2-base | base | 860.0/s | 860.0/s | 34400 | 0 | 2.0 ms | 8.4 ms | 25.7 ms | n/a | 148.5 ms | 0.79 cores | kafka 55% · postgres 57% | 0.44 of 1 | server 0.79 | 2.22 | 85.6 KB | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed | E4-list p50 | E4-list p99 | E4-list failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 2.6 ms | 35.3 ms | 0 | 1.3 ms | 21.5 ms | 0 | 2.5 ms | 26.4 ms | 0 |
| r1-head | head | 11927.6 ms | 17301.5 ms | 0 | 11927.6 ms | 17301.5 ms | 0 | 11927.6 ms | 17301.5 ms | 435 |
| r2-head | head | 10551.3 ms | 15335.4 ms | 0 | 10551.3 ms | 15204.4 ms | 0 | 10420.2 ms | 11796.5 ms | 724 |
| r2-base | base | 2.6 ms | 25.0 ms | 0 | 1.3 ms | 14.8 ms | 0 | 2.5 ms | 20.1 ms | 0 |
| r3-base | base | 2.5 ms | 34.8 ms | 0 | 1.2 ms | 18.9 ms | 0 | 2.4 ms | 23.3 ms | 0 |
| r3-head | head | 12189.7 ms | 17563.6 ms | 88 | 12189.7 ms | 17170.4 ms | 74 | 12058.6 ms | 13434.9 ms | 848 |
| e1-base | base | 2.6 ms | 21.6 ms | 0 | 1.3 ms | 11.9 ms | 0 | 2.5 ms | 17.5 ms | 0 |
| e1-head | head | 1.6 ms | 20.7 ms | 0 | 1.1 ms | 11.3 ms | 0 | 2.1 ms | 15.2 ms | 0 |
| e2-head | head | 1.6 ms | 23.6 ms | 0 | 1.1 ms | 14.3 ms | 0 | 2.2 ms | 22.3 ms | 0 |
| e2-base | base | 2.5 ms | 30.5 ms | 0 | 1.2 ms | 17.8 ms | 0 | 2.3 ms | 23.6 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.633 | 5607.687 | 1407.316 | 595.474 | - | 179.315 | 0.606 | 0.792 |
| 2 | 1.656 | 4998.245 | 1388.770 | 745.636 | - | 145.232 | 0.611 | 0.830 |
| 3 | 1.644 | 5946.130 | 1464.048 | 634.615 | - | 197.058 | 0.614 | 0.782 |

Pairs at one rate for both arms, 860.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.693 | 0.685 | 0.936 | - | 2.760 | 0.915 | 0.790 |
| 2 | 1.000 | 0.737 | 0.610 | 0.781 | - | 0.883 | 0.939 | 0.799 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-pg@100k | r1-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| mixed-pg@100k | r1-head | steady | **error spike** | 1515 of 57687 failed: generator-overload 1080 · E4-list:read-http-429 435 |
| mixed-pg@100k | r2-head | steady | **load generator saturated** | 0.96 of 1 cores: the run measured the generator |
| mixed-pg@100k | r2-head | steady | **error spike** | 724 of 57687 failed: E4-list:read-http-429 724 |
| mixed-pg@100k | r3-head | steady | **load generator saturated** | 0.96 of 1 cores: the run measured the generator |
| mixed-pg@100k | r3-head | steady | **error spike** | 1137 of 57687 failed: E4-list:read-http-429 832 · generator-overload 127 · E1:exception-ConnectException 88 · E4-state:exception-ConnectException 74 · E4-list:exception-ConnectException 16 |

