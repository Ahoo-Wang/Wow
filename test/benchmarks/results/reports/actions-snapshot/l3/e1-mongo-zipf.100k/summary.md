## e1-mongo-zipf@100k

E1 write on MongoDB, Zipf keys (hot accounts, lane and cache contention): head against baseline.

E1, zipf keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (60–63 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1770.9/s | 1024 | 1416.7/s | 80% |
| head | 1760.3/s | 1024 | 1408.2/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1416.7/s | 1416.7/s | 28334 | 0 | 76.3 ms | 331.8 ms | 577.5 ms | n/a | 778.2 ms | 0.89 cores | kafka 50% · mongo 96% | 0.63 of 1 | mongo 0.96 | 1.66 | 97.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| probe1-head | head | 1408.2/s | 1408.2/s | 28164 | 0 | 81.4 ms | 387.1 ms | 675.8 ms | n/a | 1466.4 ms | 0.88 cores | kafka 52% · mongo 93% | 0.63 of 1 | mongo 0.93 | 1.66 | 96.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1416.7/s | 1416.7/s | 56668 | 0 | 48.1 ms | 227.3 ms | 368.6 ms | n/a | 593.9 ms | 0.75 cores | kafka 51% · mongo 98% | 0.45 of 1 | mongo 0.98 | 1.59 | 98.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r1-head | head | 1408.2/s | 1408.2/s | 56328 | 0 | 54.5 ms | 294.9 ms | 452.6 ms | n/a | 626.7 ms | 0.78 cores | kafka 52% · mongo 97% | 0.45 of 1 | mongo 0.97 | 1.61 | 99.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| r2-head | head | 1408.2/s | 1408.2/s | 56328 | 0 | 75.3 ms | 354.3 ms | 532.5 ms | n/a | 725.0 ms | 0.82 cores | kafka 52% · mongo 97% | 0.44 of 1 | mongo 0.97 | 1.64 | 97.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r2-base | base | 1416.7/s | 1416.7/s | 56668 | 0 | 52.5 ms | 219.1 ms | 360.4 ms | n/a | 630.8 ms | 0.78 cores | kafka 52% · mongo 97% | 0.47 of 1 | mongo 0.97 | 1.60 | 98.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-base | base | 1416.7/s | 1416.7/s | 56668 | 0 | 58.1 ms | 274.4 ms | 495.6 ms | n/a | 880.6 ms | 0.77 cores | kafka 51% · mongo 98% | 0.46 of 1 | mongo 0.98 | 1.59 | 96.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r3-head | head | 1408.2/s | 1408.2/s | 56328 | 0 | 47.9 ms | 266.2 ms | 450.6 ms | n/a | 708.6 ms | 0.77 cores | kafka 46% · mongo 98% | 0.43 of 1 | mongo 0.98 | 1.57 | 100.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e1-base | base | 1408.2/s | 1408.2/s | 56328 | 0 | 54.8 ms | 250.9 ms | 403.5 ms | n/a | 518.1 ms | 0.77 cores | kafka 52% · mongo 98% | 0.44 of 1 | mongo 0.98 | 1.61 | 97.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e1-head | head | 1408.2/s | 1408.2/s | 56328 | 0 | 75.3 ms | 471.0 ms | 790.5 ms | n/a | 1253.4 ms | 0.81 cores | kafka 53% · mongo 97% | 0.44 of 1 | mongo 0.97 | 1.64 | 98.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| e2-head | head | 1408.2/s | 1408.2/s | 56328 | 0 | 64.8 ms | 311.3 ms | 501.8 ms | n/a | 745.5 ms | 0.80 cores | kafka 53% · mongo 97% | 0.44 of 1 | mongo 0.97 | 1.63 | 98.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e2-base | base | 1408.2/s | 1408.2/s | 56328 | 0 | 53.0 ms | 292.9 ms | 460.8 ms | n/a | 712.7 ms | 0.79 cores | kafka 51% · mongo 98% | 0.44 of 1 | mongo 0.98 | 1.62 | 97.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.994 | 1.133 | 1.297 | 1.228 | - | 1.055 | 1.018 | 1.012 |
| 2 | 0.994 | 1.434 | 1.617 | 1.477 | - | 1.149 | 1.024 | 0.992 |
| 3 | 0.994 | 0.824 | 0.970 | 0.909 | - | 0.805 | 0.982 | 1.045 |

Pairs at one rate for both arms, 1408.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.374 | 1.878 | 1.959 | - | 2.419 | 1.017 | 1.005 |
| 2 | 1.000 | 1.222 | 1.063 | 1.089 | - | 1.046 | 1.009 | 1.009 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-zipf@100k | r1-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r1-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@100k | r2-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r3-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +27.5% (±25.0%) |
| e1-mongo-zipf@100k | r3-head | steady | **tail read the store** | overflow=4 |
| e1-mongo-zipf@100k | e1-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +51.9% (±25.0%) |
| e1-mongo-zipf@100k | e1-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.0% (±25.0%) |
| e1-mongo-zipf@100k | e1-head | steady | **p99 > 10× p50** | p50 75.3 ms, p99 790.5 ms |
| e1-mongo-zipf@100k | e2-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | e2-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@100k | probe1-base | overload (probe) | **tail read the store** | overflow=1 |
| e1-mongo-zipf@100k | probe1-head | overload (probe) | **tail read the store** | overflow=1 |

