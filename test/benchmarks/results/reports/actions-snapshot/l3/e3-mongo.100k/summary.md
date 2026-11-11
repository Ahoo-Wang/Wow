## e3-mongo@100k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (47–52 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 681.4/s | 256 | 545.1/s | 80% |
| head | 641.6/s | 256 | 513.3/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 545.1/s | 545.1/s | 10902 | 0 | 96.8 ms | 274.4 ms | 376.8 ms | n/a | 528.4 ms | 0.56 cores | kafka 38% · mongo 97% | 0.29 of 1 | mongo 0.97 | 3.50 | 266.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| probe1-head | head | 513.3/s | 513.3/s | 10265 | 0 | 115.2 ms | 393.2 ms | 495.6 ms | n/a | 589.8 ms | 0.57 cores | kafka 43% · mongo 92% | 0.32 of 1 | mongo 0.92 | 3.73 | 272.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 545.1/s | 545.1/s | 21803 | 0 | 307.2 ms | 1359.9 ms | 1728.5 ms | n/a | 1957.9 ms | 0.50 cores | kafka 40% · mongo 88% | 0.17 of 1 | mongo 0.88 | 3.25 | 254.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 23 s | - |
| r1-head | head | 513.3/s | 513.3/s | 20532 | 0 | 103.4 ms | 329.7 ms | 436.2 ms | n/a | 585.7 ms | 0.55 cores | kafka 41% · mongo 98% | 0.14 of 1 | mongo 0.98 | 3.77 | 276.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| r2-head | head | 513.3/s | 513.3/s | 20532 | 0 | 98.3 ms | 391.2 ms | 602.1 ms | n/a | 819.2 ms | 0.53 cores | kafka 40% · mongo 96% | 0.14 of 1 | mongo 0.96 | 3.69 | 273.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| r2-base | base | 545.1/s | 545.1/s | 21803 | 0 | 96.3 ms | 262.1 ms | 376.8 ms | n/a | 522.2 ms | 0.53 cores | kafka 41% · mongo 97% | 0.15 of 1 | mongo 0.97 | 3.50 | 264.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-base | base | 545.1/s | 545.1/s | 21803 | 0 | 98.3 ms | 385.0 ms | 528.4 ms | n/a | 700.4 ms | 0.53 cores | kafka 41% · mongo 96% | 0.15 of 1 | mongo 0.96 | 3.48 | 266.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| r3-head | head | 513.3/s | 513.3/s | 20532 | 0 | 117.8 ms | 725.0 ms | 897.0 ms | n/a | 987.1 ms | 0.54 cores | kafka 40% · mongo 93% | 0.17 of 1 | mongo 0.93 | 3.64 | 274.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| e1-base | base | 513.3/s | 513.3/s | 20532 | 0 | 73.2 ms | 274.4 ms | 430.1 ms | n/a | 610.3 ms | 0.52 cores | kafka 38% · mongo 95% | 0.14 of 1 | mongo 0.95 | 3.61 | 274.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e1-head | head | 513.3/s | 513.3/s | 20532 | 0 | 114.2 ms | 585.7 ms | 831.5 ms | n/a | 970.8 ms | 0.52 cores | kafka 40% · mongo 93% | 0.15 of 1 | mongo 0.93 | 3.60 | 270.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| e2-head | head | 513.3/s | 513.3/s | 20532 | 0 | 155.6 ms | 704.5 ms | 847.9 ms | n/a | 954.4 ms | 0.51 cores | kafka 40% · mongo 91% | 0.14 of 1 | mongo 0.91 | 3.54 | 266.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | - |
| e2-base | base | 513.3/s | 513.3/s | 20532 | 0 | 84.0 ms | 297.0 ms | 479.2 ms | n/a | 651.3 ms | 0.53 cores | kafka 39% · mongo 95% | 0.16 of 1 | mongo 0.95 | 3.65 | 268.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.942 | 0.337 | 0.242 | 0.252 | - | 0.299 | 1.160 | 1.089 |
| 2 | 0.942 | 1.021 | 1.492 | 1.598 | - | 1.569 | 1.054 | 1.035 |
| 3 | 0.942 | 1.198 | 1.883 | 1.698 | - | 1.409 | 1.047 | 1.033 |

Pairs at one rate for both arms, 513.3/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.559 | 2.134 | 1.933 | - | 1.591 | 0.997 | 0.982 |
| 2 | 1.000 | 1.854 | 2.372 | 1.769 | - | 1.465 | 0.972 | 0.993 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +494.1% (±25.0%) |
| e3-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -34.0% (±25.0%) |
| e3-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +52.8% (±25.0%) |
| e3-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +35.5% (±25.0%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -67.1% (±25.0%) |
| e3-mongo@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +91.1% (±25.0%) |
| e3-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +48.9% (±25.0%) |
| e3-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +78.0% (±25.0%) |

