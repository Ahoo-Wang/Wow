## large-mongo@1k

E1 with a large payload on MongoDB (D224 note ⑥): five events of about 2 KB per command. Head against baseline.

E1-large, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2257.3/s | 1024 | 1015.8/s | 45% |
| head | 1709.9/s | 32 | 769.4/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1805.8/s | 1191.0/s | 23819 | 12297 | 1540.1 ms | 2605.1 ms | 3096.6 ms | n/a | 3358.7 ms | 0.99 cores | kafka 47% · mongo 49% | 0.40 of 1 | server 0.99 | 1.63 | 149.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | generator-overload 12297 |
| probe2-base | base | 1354.4/s | 1240.8/s | 24816 | 2272 | 1368.1 ms | 2310.1 ms | 2768.9 ms | n/a | 3080.2 ms | 1.00 cores | kafka 44% · mongo 50% | 0.38 of 1 | server 1.00 | 1.56 | 209.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | generator-overload 2272 |
| probe3-base | base | 1015.8/s | 1015.8/s | 20316 | 0 | 395.3 ms | 1523.7 ms | 2023.4 ms | n/a | 2523.1 ms | 1.00 cores | kafka 46% · mongo 58% | 0.36 of 1 | server 1.00 | 2.00 | 249.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| probe1-head | head | 1367.9/s | 1159.8/s | 23197 | 4161 | 1490.9 ms | 2523.1 ms | 2998.3 ms | n/a | 3506.2 ms | 1.00 cores | kafka 46% · mongo 49% | 0.41 of 1 | server 1.00 | 1.68 | 198.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 4161 |
| probe2-head | head | 1025.9/s | 1014.5/s | 20290 | 228 | 1286.1 ms | 2474.0 ms | 2932.7 ms | n/a | 3473.4 ms | 1.00 cores | kafka 47% · mongo 51% | 0.38 of 1 | server 1.00 | 1.95 | 244.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | generator-overload 228 |
| probe3-head | head | 769.4/s | 769.4/s | 15388 | 0 | 124.4 ms | 1048.6 ms | 1499.1 ms | n/a | 1908.7 ms | 1.00 cores | kafka 49% · mongo 60% | 0.30 of 1 | server 1.00 | 2.71 | 263.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1015.8/s | 1015.8/s | 40632 | 0 | 31.6 ms | 444.4 ms | 770.0 ms | n/a | 1146.9 ms | 0.97 cores | kafka 42% · mongo 73% | 0.25 of 1 | server 0.97 | 2.08 | 242.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r1-head | head | 769.4/s | 769.4/s | 30776 | 0 | 20.9 ms | 593.9 ms | 1003.5 ms | n/a | 1441.8 ms | 0.95 cores | kafka 44% · mongo 74% | 0.22 of 1 | server 0.95 | 2.77 | 250.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r2-head | head | 769.4/s | 769.4/s | 30776 | 0 | 24.8 ms | 423.9 ms | 766.0 ms | n/a | 1146.9 ms | 0.98 cores | kafka 43% · mongo 70% | 0.21 of 1 | server 0.98 | 2.75 | 251.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r2-base | base | 1015.8/s | 1015.8/s | 40632 | 0 | 51.7 ms | 520.2 ms | 843.8 ms | n/a | 1261.6 ms | 0.98 cores | kafka 45% · mongo 71% | 0.27 of 1 | server 0.98 | 2.11 | 237.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-base | base | 1015.8/s | 1015.8/s | 40632 | 0 | 48.9 ms | 569.3 ms | 921.6 ms | n/a | 1278.0 ms | 0.99 cores | kafka 43% · mongo 71% | 0.25 of 1 | server 0.99 | 2.10 | 238.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-head | head | 769.4/s | 769.4/s | 30776 | 0 | 22.8 ms | 337.9 ms | 659.5 ms | n/a | 991.2 ms | 0.98 cores | kafka 43% · mongo 72% | 0.21 of 1 | server 0.98 | 2.77 | 256.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e1-base | base | 769.4/s | 769.4/s | 30776 | 0 | 15.3 ms | 335.9 ms | 626.7 ms | n/a | 929.8 ms | 0.96 cores | kafka 44% · mongo 74% | 0.21 of 1 | server 0.96 | 2.78 | 254.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e1-head | head | 769.4/s | 769.4/s | 30776 | 0 | 15.0 ms | 378.9 ms | 737.3 ms | n/a | 1171.5 ms | 0.96 cores | kafka 41% · mongo 72% | 0.20 of 1 | server 0.96 | 2.72 | 260.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e2-head | head | 769.4/s | 769.4/s | 30776 | 0 | 19.6 ms | 450.6 ms | 831.5 ms | n/a | 1237.0 ms | 0.93 cores | kafka 42% · mongo 71% | 0.21 of 1 | server 0.93 | 2.68 | 253.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e2-base | base | 769.4/s | 769.4/s | 30776 | 0 | 13.9 ms | 292.9 ms | 622.6 ms | n/a | 1007.6 ms | 0.97 cores | kafka 41% · mongo 74% | 0.21 of 1 | server 0.97 | 2.75 | 257.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.757 | 0.660 | 1.336 | 1.303 | - | 1.257 | 1.331 | 1.031 |
| 2 | 0.757 | 0.480 | 0.815 | 0.908 | - | 0.909 | 1.305 | 1.061 |
| 3 | 0.757 | 0.466 | 0.594 | 0.716 | - | 0.776 | 1.321 | 1.074 |

Pairs at one rate for both arms, 769.4/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.983 | 1.128 | 1.176 | - | 1.260 | 0.977 | 1.027 |
| 2 | 1.000 | 1.410 | 1.538 | 1.336 | - | 1.228 | 0.973 | 0.984 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.6% (±25.0%) |
| large-mongo@1k | r1-base | steady | **p99 > 10× p50** | p50 31.6 ms, p99 770.0 ms |
| large-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.9% (±25.0%) |
| large-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 20.9 ms, p99 1003.5 ms |
| large-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -27.2% (±25.0%) |
| large-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 24.8 ms, p99 766.0 ms |
| large-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -78.6% (±25.0%) |
| large-mongo@1k | r2-base | steady | **p99 > 10× p50** | p50 51.7 ms, p99 843.8 ms |
| large-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.5% (±25.0%) |
| large-mongo@1k | r3-base | steady | **p99 > 10× p50** | p50 48.9 ms, p99 921.6 ms |
| large-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -56.9% (±25.0%) |
| large-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 22.8 ms, p99 659.5 ms |
| large-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -62.9% (±25.0%) |
| large-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 15.3 ms, p99 626.7 ms |
| large-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -51.7% (±25.0%) |
| large-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 15.0 ms, p99 737.3 ms |
| large-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.9% (±25.0%) |
| large-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 19.6 ms, p99 831.5 ms |
| large-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -41.9% (±25.0%) |
| large-mongo@1k | e2-base | steady | **p99 > 10× p50** | p50 13.9 ms, p99 622.6 ms |

