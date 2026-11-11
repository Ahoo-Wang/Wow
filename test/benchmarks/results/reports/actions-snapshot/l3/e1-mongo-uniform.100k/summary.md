## e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (33–40 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 3468.8/s | 2048 | 2775.0/s | 80% |
| head | 3128.9/s | 2048 | 2503.1/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 2775.0/s | 2775.0/s | 55500 | 0 | 148.5 ms | 438.3 ms | 553.0 ms | n/a | 610.3 ms | 0.76 cores | kafka 36% · mongo 97% | 0.53 of 1 | mongo 0.97 | 0.75 | 92.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| probe1-head | head | 2503.1/s | 2503.1/s | 50062 | 0 | 98.8 ms | 297.0 ms | 397.3 ms | n/a | 495.6 ms | 0.75 cores | kafka 37% · mongo 94% | 0.50 of 1 | mongo 0.94 | 0.83 | 95.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 2775.0/s | 2775.0/s | 111000 | 0 | 193.5 ms | 532.5 ms | 725.0 ms | n/a | 991.2 ms | 0.71 cores | kafka 37% · mongo 97% | 0.49 of 1 | mongo 0.97 | 0.74 | 92.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 19 s | - |
| r1-head | head | 2503.1/s | 2503.1/s | 100124 | 0 | 95.7 ms | 335.9 ms | 487.4 ms | n/a | 647.2 ms | 0.70 cores | kafka 36% · mongo 96% | 0.45 of 1 | mongo 0.96 | 0.81 | 97.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| r2-head | head | 2503.1/s | 2503.1/s | 100124 | 0 | 146.4 ms | 428.0 ms | 622.6 ms | n/a | 737.3 ms | 0.70 cores | kafka 37% · mongo 95% | 0.45 of 1 | mongo 0.95 | 0.81 | 95.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 21 s | - |
| r2-base | base | 2775.0/s | 2772.2/s | 110887 | 113 | 214.0 ms | 598.0 ms | 815.1 ms | n/a | 987.1 ms | 0.73 cores | kafka 37% · mongo 97% | 0.51 of 1 | mongo 0.97 | 0.75 | 91.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 24 s | generator-overload 113 |
| r3-base | base | 2775.0/s | 2775.0/s | 111000 | 0 | 189.4 ms | 501.8 ms | 626.7 ms | n/a | 835.6 ms | 0.73 cores | kafka 36% · mongo 97% | 0.50 of 1 | mongo 0.97 | 0.74 | 92.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 20 s | - |
| r3-head | head | 2503.1/s | 2503.1/s | 100124 | 0 | 147.5 ms | 557.1 ms | 819.2 ms | n/a | 983.0 ms | 0.71 cores | kafka 37% · mongo 96% | 0.46 of 1 | mongo 0.96 | 0.82 | 95.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 24 s | - |
| e1-base | base | 2503.1/s | 2503.1/s | 100124 | 0 | 268.3 ms | 589.8 ms | 737.3 ms | n/a | 913.4 ms | 0.72 cores | kafka 42% · mongo 98% | 0.49 of 1 | mongo 0.98 | 0.85 | 91.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 19 s | - |
| e1-head | head | 2503.1/s | 2499.6/s | 99984 | 140 | 288.8 ms | 663.6 ms | 831.5 ms | n/a | 1019.9 ms | 0.74 cores | kafka 39% · mongo 95% | 0.48 of 1 | mongo 0.95 | 0.83 | 94.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 29 s | generator-overload 140 |
| e2-head | head | 2503.1/s | 2472.1/s | 98883 | 1241 | 331.8 ms | 757.8 ms | 978.9 ms | n/a | 1384.4 ms | 0.73 cores | kafka 40% · mongo 98% | 0.49 of 1 | mongo 0.98 | 0.85 | 91.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 25 s | generator-overload 1241 |
| e2-base | base | 2503.1/s | 2503.1/s | 100124 | 0 | 112.6 ms | 358.4 ms | 528.4 ms | n/a | 798.7 ms | 0.69 cores | kafka 37% · mongo 96% | 0.46 of 1 | mongo 0.96 | 0.81 | 93.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.902 | 0.495 | 0.631 | 0.672 | - | 0.653 | 1.099 | 1.054 |
| 2 | 0.903 | 0.684 | 0.716 | 0.764 | - | 0.747 | 1.080 | 1.038 |
| 3 | 0.902 | 0.778 | 1.110 | 1.307 | - | 1.176 | 1.100 | 1.034 |

Pairs at one rate for both arms, 2503.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.999 | 1.076 | 1.125 | 1.128 | - | 1.117 | 0.982 | 1.023 |
| 2 | 0.988 | 2.945 | 2.114 | 1.853 | - | 1.733 | 1.058 | 0.979 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +25.1% (±25.0%) |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +103.5% (±25.0%) |
| e1-mongo-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +40.2% (±25.0%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +62.1% (±25.0%) |
| e1-mongo-uniform@100k | e2-head | steady | **error spike** | 1241 of 100124 failed: generator-overload 1241; generator-overload: 2000 in flight at most (per-target ceiling) |

