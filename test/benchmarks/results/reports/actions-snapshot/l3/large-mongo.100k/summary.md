## large-mongo@100k

E1 with a large payload on MongoDB (D224 note ⑥): five events of about 2 KB per command. Head against baseline.

E1-large, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (70–79 s), then waited until the nodes were quiet again (4–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1098.8/s | 2048 | 659.3/s | 60% |
| head | 1008.8/s | 256 | 605.3/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 879.0/s | 842.8/s | 16855 | 725 | 1851.4 ms | 2867.2 ms | 3162.1 ms | n/a | 3588.1 ms | 0.99 cores | kafka 47% · mongo 96% | 0.46 of 1 | server 0.99 | 2.87 | 212.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | generator-overload 725 |
| probe2-base | base | 659.3/s | 659.3/s | 13186 | 0 | 68.6 ms | 290.8 ms | 438.3 ms | n/a | 569.3 ms | 0.76 cores | kafka 47% · mongo 96% | 0.45 of 1 | mongo 0.96 | 3.33 | 236.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| probe1-head | head | 807.0/s | 767.6/s | 15353 | 787 | 1933.3 ms | 3162.1 ms | 3637.2 ms | n/a | 4030.5 ms | 0.96 cores | kafka 55% · mongo 91% | 0.55 of 1 | server 0.96 | 3.15 | 213.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | generator-overload 787 |
| probe2-head | head | 605.3/s | 605.3/s | 12106 | 0 | 61.4 ms | 237.6 ms | 352.3 ms | n/a | 438.3 ms | 0.78 cores | kafka 52% · mongo 96% | 0.49 of 1 | mongo 0.96 | 3.74 | 240.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 659.3/s | 659.3/s | 26372 | 0 | 49.2 ms | 194.6 ms | 309.2 ms | n/a | 466.9 ms | 0.72 cores | kafka 52% · mongo 96% | 0.28 of 1 | mongo 0.96 | 3.34 | 238.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r1-head | head | 605.3/s | 605.3/s | 24212 | 0 | 50.7 ms | 231.4 ms | 319.5 ms | n/a | 475.1 ms | 0.68 cores | kafka 48% · mongo 95% | 0.25 of 1 | mongo 0.95 | 3.49 | 243.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| r2-head | head | 605.3/s | 605.3/s | 24212 | 0 | 54.5 ms | 258.0 ms | 401.4 ms | n/a | 593.9 ms | 0.70 cores | kafka 51% · mongo 96% | 0.25 of 1 | mongo 0.96 | 3.58 | 243.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r2-base | base | 659.3/s | 659.3/s | 26372 | 0 | 54.8 ms | 280.6 ms | 387.1 ms | n/a | 514.0 ms | 0.66 cores | kafka 48% · mongo 97% | 0.26 of 1 | mongo 0.97 | 3.19 | 238.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| r3-base | base | 659.3/s | 659.3/s | 26372 | 0 | 62.7 ms | 247.8 ms | 376.8 ms | n/a | 569.3 ms | 0.67 cores | kafka 48% · mongo 98% | 0.27 of 1 | mongo 0.98 | 3.24 | 236.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r3-head | head | 605.3/s | 605.3/s | 24212 | 0 | 53.0 ms | 228.4 ms | 346.1 ms | n/a | 479.2 ms | 0.64 cores | kafka 50% · mongo 96% | 0.24 of 1 | mongo 0.96 | 3.47 | 244.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e1-base | base | 605.3/s | 605.3/s | 24212 | 0 | 63.2 ms | 249.9 ms | 370.7 ms | n/a | 593.9 ms | 0.66 cores | kafka 51% · mongo 98% | 0.25 of 1 | mongo 0.98 | 3.53 | 238.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| e1-head | head | 605.3/s | 605.3/s | 24212 | 0 | 59.4 ms | 198.7 ms | 264.2 ms | n/a | 462.8 ms | 0.64 cores | kafka 50% · mongo 97% | 0.24 of 1 | mongo 0.97 | 3.47 | 241.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e2-head | head | 605.3/s | 605.3/s | 24212 | 0 | 39.2 ms | 171.0 ms | 264.2 ms | n/a | 505.9 ms | 0.69 cores | kafka 52% · mongo 95% | 0.25 of 1 | mongo 0.95 | 3.56 | 244.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e2-base | base | 605.3/s | 605.3/s | 24212 | 0 | 42.5 ms | 175.1 ms | 251.9 ms | n/a | 362.5 ms | 0.65 cores | kafka 48% · mongo 96% | 0.26 of 1 | mongo 0.96 | 3.47 | 241.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.918 | 1.031 | 1.189 | 1.033 | - | 1.018 | 1.043 | 1.019 |
| 2 | 0.918 | 0.995 | 0.920 | 1.037 | - | 1.155 | 1.124 | 1.019 |
| 3 | 0.918 | 0.845 | 0.921 | 0.918 | - | 0.842 | 1.071 | 1.032 |

Pairs at one rate for both arms, 605.3/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.939 | 0.795 | 0.713 | - | 0.779 | 0.984 | 1.015 |
| 2 | 1.000 | 0.922 | 0.977 | 1.049 | - | 1.395 | 1.025 | 1.012 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +38.1% (±25.0%) |
| large-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +64.5% (±25.0%) |
| large-mongo@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.1% (±25.0%) |
| large-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +71.5% (±25.0%) |
| large-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +123.1% (±25.0%) |
| large-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +42.0% (±25.0%) |

