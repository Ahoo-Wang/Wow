## e2-mongo@100k

E2 read-your-write on MongoDB: head against baseline.

E2, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (66–74 s), then waited until the nodes were quiet again (3–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 959.4/s | 2048 | 575.6/s | 60% |
| head | 934.6/s | 1024 | 747.7/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 767.5/s | 743.0/s | 14860 | 490 | 2048.0 ms | 3244.0 ms | 3670.0 ms | n/a | 4227.1 ms | 0.95 cores | kafka 44% · mongo 93% | 0.59 of 1 | server 0.95 | 3.12 | 168.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 20 s | generator-overload 490 |
| probe2-base | base | 575.6/s | 575.6/s | 11512 | 0 | 120.3 ms | 419.8 ms | 610.3 ms | n/a | 790.5 ms | 0.83 cores | kafka 50% · mongo 94% | 0.57 of 1 | mongo 0.94 | 3.95 | 188.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| probe1-head | head | 747.7/s | 745.5/s | 14911 | 43 | 1622.0 ms | 2703.4 ms | 3407.9 ms | n/a | 4014.1 ms | 0.88 cores | kafka 47% · mongo 96% | 0.67 of 1 | mongo 0.96 | 3.10 | 182.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | generator-overload 43 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 575.6/s | 575.6/s | 23024 | 0 | 72.2 ms | 169.0 ms | 216.1 ms | n/a | 290.8 ms | 0.74 cores | kafka 50% · mongo 96% | 0.36 of 1 | mongo 0.96 | 3.81 | 190.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r1-head | head | 747.7/s | 747.7/s | 29908 | 0 | 139.3 ms | 426.0 ms | 626.7 ms | n/a | 872.4 ms | 0.79 cores | kafka 50% · mongo 97% | 0.44 of 1 | mongo 0.97 | 3.02 | 180.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| r2-head | head | 747.7/s | 747.7/s | 29908 | 0 | 391.2 ms | 696.3 ms | 942.1 ms | n/a | 1376.3 ms | 0.87 cores | kafka 51% · mongo 97% | 0.44 of 1 | mongo 0.97 | 3.15 | 175.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | - |
| r2-base | base | 575.6/s | 575.6/s | 23024 | 0 | 65.5 ms | 212.0 ms | 346.1 ms | n/a | 499.7 ms | 0.71 cores | kafka 49% · mongo 97% | 0.37 of 1 | mongo 0.97 | 3.77 | 190.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-base | base | 575.6/s | 575.6/s | 23024 | 0 | 70.1 ms | 180.2 ms | 246.8 ms | n/a | 337.9 ms | 0.74 cores | kafka 46% · mongo 97% | 0.37 of 1 | mongo 0.97 | 3.76 | 189.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| r3-head | head | 747.7/s | 747.7/s | 29908 | 0 | 200.7 ms | 483.3 ms | 634.9 ms | n/a | 852.0 ms | 0.81 cores | kafka 50% · mongo 98% | 0.43 of 1 | mongo 0.98 | 3.07 | 176.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e1-base | base | 575.6/s | 575.6/s | 23024 | 0 | 71.2 ms | 200.7 ms | 272.4 ms | n/a | 387.1 ms | 0.74 cores | kafka 51% · mongo 97% | 0.38 of 1 | mongo 0.97 | 3.85 | 190.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e1-head | head | 575.6/s | 575.6/s | 23024 | 0 | 100.4 ms | 315.4 ms | 421.9 ms | n/a | 630.8 ms | 0.72 cores | kafka 50% · mongo 97% | 0.38 of 1 | mongo 0.97 | 3.81 | 186.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e2-head | head | 575.6/s | 575.6/s | 23024 | 0 | 70.7 ms | 184.3 ms | 254.0 ms | n/a | 362.5 ms | 0.75 cores | kafka 46% · mongo 95% | 0.37 of 1 | mongo 0.95 | 3.76 | 192.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e2-base | base | 575.6/s | 575.6/s | 23024 | 0 | 73.2 ms | 264.2 ms | 372.7 ms | n/a | 477.2 ms | 0.73 cores | kafka 51% · mongo 96% | 0.36 of 1 | mongo 0.96 | 3.84 | 189.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.299 | 1.929 | 2.521 | 2.900 | - | 3.000 | 0.793 | 0.949 |
| 2 | 1.299 | 5.969 | 3.285 | 2.722 | - | 2.754 | 0.834 | 0.921 |
| 3 | 1.299 | 2.861 | 2.682 | 2.573 | - | 2.521 | 0.815 | 0.931 |

Pairs at one rate for both arms, 575.6/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.410 | 1.571 | 1.549 | - | 1.630 | 0.991 | 0.978 |
| 2 | 1.000 | 0.965 | 0.698 | 0.681 | - | 0.760 | 0.981 | 1.015 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e2-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -26.3% (±25.0%) |

