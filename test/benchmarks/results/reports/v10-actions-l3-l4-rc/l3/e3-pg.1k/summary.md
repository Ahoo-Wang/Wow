## e3-pg@1k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 787.4/s | 64 | 265.7/s | 34% |
| head | 1301.5/s | 1024 | 439.3/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 629.9/s | 1.8/s | 35 | 12563 | 9568.3 ms | 11010.0 ms | 11288.2 ms | n/a | 11288.2 ms | 1.77 cores | kafka 71% · postgres 41% | 0.76 of 1 | server 1.77 | 1648.41 | 131.7 KB | http-504-wow.wait.timeout 7525 · http-429-wow.command.busy 5038 |
| probe2-base | base | 472.4/s | 66.6/s | 1332 | 8116 | 8159.2 ms | 10551.3 ms | 10944.5 ms | n/a | 11212.6 ms | 1.68 cores | kafka 67% · postgres 52% | 0.67 of 1 | server 1.68 | 43.24 | 192.6 KB | http-504-wow.wait.timeout 8116 |
| probe3-base | base | 354.3/s | 340.8/s | 6816 | 270 | 3326.0 ms | 8290.3 ms | 9764.9 ms | n/a | 10223.6 ms | 1.65 cores | kafka 65% · postgres 61% | 0.52 of 1 | server 1.65 | 8.51 | 292.4 KB | http-504-wow.wait.timeout 270 |
| probe4-base | base | 265.7/s | 265.7/s | 5314 | 0 | 57.6 ms | 1753.1 ms | 2703.4 ms | n/a | 3555.3 ms | 1.60 cores | kafka 71% · postgres 61% | 0.39 of 1 | server 1.60 | 11.00 | 313.9 KB | - |
| probe1-head | head | 1041.2/s | 2.8/s | 55 | 20769 | 13238.3 ms | 15071.9 ms | 15071.9 ms | n/a | 15071.9 ms | 1.81 cores | kafka 80% · postgres 29% | 0.94 of 1 | server 1.81 | 1053.26 | 107.5 KB | http-504-wow.wait.timeout 11698 · http-429-wow.command.busy 9071 |
| probe2-head | head | 780.9/s | 5.0/s | 101 | 15517 | 10747.9 ms | 12255.2 ms | 12648.4 ms | n/a | 12697.0 ms | 1.77 cores | kafka 81% · postgres 31% | 0.89 of 1 | server 1.77 | 573.44 | 137.4 KB | http-504-wow.wait.timeout 11755 · http-429-wow.command.busy 3762 |
| probe3-head | head | 585.7/s | 39.4/s | 788 | 10926 | 9044.0 ms | 10616.8 ms | 11075.6 ms | n/a | 11498.3 ms | 1.72 cores | kafka 81% · postgres 39% | 0.72 of 1 | server 1.72 | 74.03 | 204.2 KB | http-504-wow.wait.timeout 10926 |
| probe4-head | head | 439.3/s | 435.4/s | 8707 | 78 | 3129.3 ms | 7602.2 ms | 9437.2 ms | n/a | 10223.6 ms | 1.73 cores | kafka 77% · postgres 44% | 0.67 of 1 | server 1.73 | 6.76 | 269.4 KB | http-504-wow.wait.timeout 78 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 265.7/s | 265.7/s | 10628 | 0 | 4.5 ms | 31.0 ms | 102.4 ms | n/a | 247.8 ms | 1.23 cores | kafka 56% · postgres 64% | 0.23 of 1 | server 1.23 | 9.15 | 281.9 KB | - |
| r1-head | head | 439.3/s | 439.3/s | 17571 | 0 | 29.8 ms | 256.0 ms | 458.8 ms | n/a | 692.2 ms | 1.57 cores | kafka 52% · postgres 79% | 0.29 of 1 | server 1.57 | 6.56 | 219.9 KB | - |
| r2-head | head | 439.3/s | 439.3/s | 17571 | 0 | 31.0 ms | 385.0 ms | 618.5 ms | n/a | 942.1 ms | 1.57 cores | kafka 55% · postgres 78% | 0.29 of 1 | server 1.57 | 6.61 | 220.1 KB | - |
| r2-base | base | 265.7/s | 265.7/s | 10628 | 0 | 4.2 ms | 28.9 ms | 84.0 ms | n/a | 303.1 ms | 1.20 cores | kafka 60% · postgres 65% | 0.21 of 1 | server 1.20 | 9.23 | 280.7 KB | - |
| r3-base | base | 265.7/s | 265.7/s | 10628 | 0 | 4.3 ms | 40.2 ms | 116.7 ms | n/a | 385.0 ms | 1.20 cores | kafka 59% · postgres 64% | 0.24 of 1 | server 1.20 | 9.13 | 285.0 KB | - |
| r3-head | head | 439.3/s | 439.3/s | 17571 | 0 | 25.7 ms | 307.2 ms | 505.9 ms | n/a | 733.2 ms | 1.55 cores | kafka 54% · postgres 78% | 0.31 of 1 | server 1.55 | 6.54 | 223.2 KB | - |
| e1-base | base | 265.7/s | 265.7/s | 10628 | 0 | 3.6 ms | 20.6 ms | 63.2 ms | n/a | 272.4 ms | 1.16 cores | kafka 57% · postgres 63% | 0.20 of 1 | server 1.16 | 8.86 | 284.0 KB | - |
| e1-head | head | 265.7/s | 265.7/s | 10628 | 0 | 6.6 ms | 63.0 ms | 152.6 ms | n/a | 303.1 ms | 1.43 cores | kafka 57% · postgres 64% | 0.27 of 1 | server 1.43 | 9.96 | 230.6 KB | - |
| e2-head | head | 265.7/s | 265.7/s | 10628 | 0 | 4.5 ms | 36.6 ms | 112.1 ms | n/a | 264.2 ms | 1.29 cores | kafka 58% · postgres 63% | 0.22 of 1 | server 1.29 | 9.41 | 228.6 KB | - |
| e2-base | base | 265.7/s | 265.7/s | 10628 | 0 | 5.3 ms | 53.0 ms | 119.8 ms | n/a | 340.0 ms | 1.28 cores | kafka 62% · postgres 64% | 0.27 of 1 | server 1.28 | 9.57 | 282.2 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.653 | 6.565 | 8.265 | 4.480 | - | 2.793 | 0.717 | 0.780 |
| 2 | 1.653 | 7.335 | 13.310 | 7.366 | - | 3.108 | 0.715 | 0.784 |
| 3 | 1.653 | 5.957 | 7.643 | 4.333 | - | 1.904 | 0.717 | 0.783 |

Pairs at one rate for both arms, 265.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.848 | 3.056 | 2.413 | - | 1.113 | 1.123 | 0.812 |
| 2 | 1.000 | 0.849 | 0.691 | 0.936 | - | 0.777 | 0.983 | 0.810 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -44.9% (±25.0%) |
| e3-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 4.5 ms, p99 102.4 ms |
| e3-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -59.7% (±25.0%) |
| e3-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 29.8 ms, p99 458.8 ms |
| e3-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -37.5% (±25.0%) |
| e3-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 31.0 ms, p99 618.5 ms |
| e3-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -45.4% (±25.0%) |
| e3-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 4.2 ms, p99 84.0 ms |
| e3-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -51.4% (±25.0%) |
| e3-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 4.3 ms, p99 116.7 ms |
| e3-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -57.5% (±25.0%) |
| e3-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 25.7 ms, p99 505.9 ms |
| e3-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -45.2% (±25.0%) |
| e3-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 3.6 ms, p99 63.2 ms |
| e3-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.7% (±25.0%) |
| e3-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 6.6 ms, p99 152.6 ms |
| e3-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.3% (±25.0%) |
| e3-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 4.5 ms, p99 112.1 ms |
| e3-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -51.3% (±25.0%) |
| e3-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 5.3 ms, p99 119.8 ms |
| e3-pg@1k | probe1-base | overload (probe) | **tail read the store** | gap=3 |
| e3-pg@1k | probe2-base | overload (probe) | **tail read the store** | gap=2 |

