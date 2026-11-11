## e3-pg@100k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (66–74 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 590.5/s | 32 | 472.4/s | 80% |
| head | 717.5/s | 512 | 430.5/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 472.4/s | 472.4/s | 9448 | 0 | 335.9 ms | 880.6 ms | 1073.2 ms | n/a | 1204.2 ms | 0.81 cores | kafka 43% · postgres 73% | 0.36 of 2 | server 0.81 | 4.18 | 238.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 8 s | - |
| probe1-head | head | 574.0/s | 548.0/s | 10959 | 521 | 1843.2 ms | 4325.4 ms | 4849.7 ms | n/a | 5242.9 ms | 0.93 cores | kafka 45% · postgres 61% | 0.44 of 2 | server 0.93 | 3.62 | 246.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 16 s | generator-overload 521 |
| probe2-head | head | 430.5/s | 430.5/s | 8610 | 0 | 179.2 ms | 954.4 ms | 1130.5 ms | n/a | 1269.8 ms | 0.82 cores | kafka 42% · postgres 73% | 0.35 of 2 | server 0.82 | 4.58 | 260.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 472.4/s | 472.4/s | 18896 | 0 | 83.5 ms | 290.8 ms | 395.3 ms | n/a | 528.4 ms | 0.82 cores | kafka 49% · postgres 69% | 0.20 of 2 | server 0.82 | 4.24 | 231.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r1-head | head | 430.5/s | 430.5/s | 17220 | 0 | 38.4 ms | 247.8 ms | 321.5 ms | n/a | 401.4 ms | 0.83 cores | kafka 48% · postgres 69% | 0.18 of 2 | server 0.83 | 4.65 | 250.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| r2-head | head | 430.5/s | 430.5/s | 17220 | 0 | 64.5 ms | 452.6 ms | 618.5 ms | n/a | 774.1 ms | 0.79 cores | kafka 49% · postgres 72% | 0.19 of 2 | server 0.79 | 4.66 | 252.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 5 s | - |
| r2-base | base | 472.4/s | 472.4/s | 18896 | 0 | 54.5 ms | 238.6 ms | 315.4 ms | n/a | 452.6 ms | 0.76 cores | kafka 49% · postgres 75% | 0.20 of 2 | server 0.76 | 4.24 | 232.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| r3-base | base | 472.4/s | 472.4/s | 18896 | 0 | 137.2 ms | 725.0 ms | 905.2 ms | n/a | 1024.0 ms | 0.77 cores | kafka 51% · postgres 72% | 0.22 of 2 | server 0.77 | 4.24 | 231.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 5 s | - |
| r3-head | head | 430.5/s | 430.5/s | 17220 | 0 | 53.0 ms | 368.6 ms | 618.5 ms | n/a | 770.0 ms | 0.79 cores | kafka 48% · postgres 72% | 0.19 of 2 | server 0.79 | 4.64 | 252.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| e1-base | base | 430.5/s | 430.5/s | 17220 | 0 | 24.8 ms | 344.1 ms | 598.0 ms | n/a | 708.6 ms | 0.77 cores | kafka 49% · postgres 73% | 0.18 of 2 | server 0.77 | 4.62 | 242.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| e1-head | head | 430.5/s | 430.5/s | 17220 | 0 | 40.4 ms | 228.4 ms | 321.5 ms | n/a | 544.8 ms | 0.79 cores | kafka 49% · postgres 73% | 0.18 of 2 | server 0.79 | 4.65 | 251.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e2-head | head | 430.5/s | 430.5/s | 17220 | 0 | 40.7 ms | 202.8 ms | 288.8 ms | n/a | 454.7 ms | 0.80 cores | kafka 48% · postgres 73% | 0.18 of 2 | server 0.80 | 4.66 | 253.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e2-base | base | 430.5/s | 430.5/s | 17220 | 0 | 28.9 ms | 151.6 ms | 209.9 ms | n/a | 303.1 ms | 0.79 cores | kafka 49% · postgres 71% | 0.18 of 2 | server 0.79 | 4.64 | 238.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.911 | 0.460 | 0.852 | 0.813 | - | 0.760 | 1.098 | 1.081 |
| 2 | 0.911 | 1.183 | 1.897 | 1.961 | - | 1.710 | 1.100 | 1.088 |
| 3 | 0.911 | 0.386 | 0.508 | 0.683 | - | 0.752 | 1.095 | 1.094 |

Pairs at one rate for both arms, 430.5/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.629 | 0.664 | 0.538 | - | 0.769 | 1.007 | 1.037 |
| 2 | 1.000 | 1.407 | 1.338 | 1.376 | - | 1.500 | 1.006 | 1.063 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.6% (±25.0%) |
| e3-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +78.5% (±25.0%) |
| e3-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -73.0% (±25.0%) |
| e3-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -41.4% (±25.0%) |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -39.9% (±25.0%) |
| e3-pg@100k | r3-head | steady | **p99 > 10× p50** | p50 53.0 ms, p99 618.5 ms |
| e3-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -27.4% (±25.0%) |
| e3-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 24.8 ms, p99 598.0 ms |
| e3-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -33.3% (±25.0%) |
| e3-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -65.4% (±25.0%) |

