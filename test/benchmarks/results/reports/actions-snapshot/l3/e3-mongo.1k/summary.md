## e3-mongo@1k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 860.1/s | 1024 | 290.3/s | 34% |
| head | 780.4/s | 1024 | 263.4/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 688.1/s | 368.1/s | 7362 | 6400 | 4325.4 ms | 8323.1 ms | 9895.9 ms | n/a | 10420.2 ms | 0.99 cores | kafka 56% · mongo 56% | 0.30 of 1 | server 0.99 | 5.73 | 147.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 6261 · http-504-wow.wait.timeout 139 |
| probe2-base | base | 516.1/s | 393.3/s | 7866 | 2456 | 4177.9 ms | 7798.8 ms | 9502.7 ms | n/a | 10420.2 ms | 1.00 cores | kafka 61% · mongo 57% | 0.33 of 1 | server 1.00 | 5.53 | 208.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | generator-overload 2335 · http-504-wow.wait.timeout 121 |
| probe3-base | base | 387.1/s | 379.7/s | 7594 | 148 | 3260.4 ms | 6914.0 ms | 8847.4 ms | n/a | 10485.8 ms | 1.00 cores | kafka 59% · mongo 59% | 0.33 of 1 | server 1.00 | 5.72 | 283.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | generator-overload 118 · http-504-wow.wait.timeout 30 |
| probe4-base | base | 290.3/s | 290.3/s | 5806 | 0 | 1114.1 ms | 3588.1 ms | 4620.3 ms | n/a | 6488.1 ms | 1.00 cores | kafka 54% · mongo 63% | 0.30 of 1 | server 1.00 | 7.47 | 323.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| probe1-head | head | 624.3/s | 319.0/s | 6380 | 6106 | 4751.4 ms | 8781.8 ms | 10027.0 ms | n/a | 10485.8 ms | 1.00 cores | kafka 59% · mongo 53% | 0.30 of 1 | server 1.00 | 6.64 | 148.6 KB | - | datasets 8 of 8 · default 6–25 of 50, lowered 2, pressed 13 s | generator-overload 5687 · http-504-wow.wait.timeout 419 |
| probe2-head | head | 468.2/s | 340.7/s | 6814 | 2550 | 4718.6 ms | 8519.7 ms | 9895.9 ms | n/a | 10616.8 ms | 1.00 cores | kafka 63% · mongo 54% | 0.34 of 1 | server 1.00 | 6.35 | 206.3 KB | - | datasets 8 of 8 · default 25–30 of 50, lowered 0, pressed 8 s | generator-overload 2339 · http-504-wow.wait.timeout 211 |
| probe3-head | head | 351.2/s | 295.8/s | 5916 | 1108 | 5210.1 ms | 9044.0 ms | 10223.6 ms | n/a | 10485.8 ms | 0.99 cores | kafka 59% · mongo 52% | 0.32 of 1 | server 0.99 | 7.11 | 240.4 KB | - | datasets 8 of 8 · default 6–25 of 50, lowered 2, pressed 15 s | generator-overload 722 · http-504-wow.wait.timeout 386 |
| probe4-head | head | 263.4/s | 263.4/s | 5267 | 0 | 1556.5 ms | 4390.9 ms | 5570.6 ms | n/a | 6979.6 ms | 0.99 cores | kafka 60% · mongo 58% | 0.28 of 1 | server 0.99 | 8.27 | 330.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 290.3/s | 290.3/s | 11612 | 0 | 39.2 ms | 138.2 ms | 230.4 ms | n/a | 411.6 ms | 0.96 cores | kafka 47% · mongo 82% | 0.15 of 1 | server 0.96 | 7.77 | 318.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r1-head | head | 263.4/s | 263.4/s | 10536 | 0 | 38.7 ms | 212.0 ms | 610.3 ms | n/a | 938.0 ms | 0.97 cores | kafka 47% · mongo 78% | 0.15 of 1 | server 0.97 | 8.41 | 340.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| r2-head | head | 263.4/s | 263.4/s | 10536 | 0 | 38.9 ms | 146.4 ms | 230.4 ms | n/a | 335.9 ms | 0.98 cores | kafka 44% · mongo 77% | 0.14 of 1 | server 0.98 | 8.33 | 344.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r2-base | base | 290.3/s | 290.3/s | 11612 | 0 | 48.6 ms | 266.2 ms | 399.4 ms | n/a | 667.6 ms | 0.94 cores | kafka 46% · mongo 77% | 0.16 of 1 | server 0.94 | 7.49 | 312.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r3-base | base | 290.3/s | 290.3/s | 11612 | 0 | 36.4 ms | 130.0 ms | 203.8 ms | n/a | 337.9 ms | 0.99 cores | kafka 46% · mongo 80% | 0.16 of 1 | server 0.99 | 7.74 | 326.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r3-head | head | 263.4/s | 263.4/s | 10536 | 0 | 33.3 ms | 157.7 ms | 458.8 ms | n/a | 843.8 ms | 0.91 cores | kafka 44% · mongo 78% | 0.14 of 1 | server 0.91 | 8.11 | 346.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e1-base | base | 263.4/s | 263.4/s | 10536 | 0 | 99.8 ms | 651.3 ms | 860.2 ms | n/a | 1114.1 ms | 0.77 cores | kafka 44% · mongo 67% | 0.15 of 1 | server 0.77 | 7.14 | 297.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e1-head | head | 263.4/s | 263.4/s | 10536 | 0 | 42.0 ms | 230.4 ms | 372.7 ms | n/a | 602.1 ms | 0.94 cores | kafka 46% · mongo 77% | 0.16 of 1 | server 0.94 | 8.23 | 337.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e2-head | head | 263.4/s | 263.4/s | 10536 | 0 | 56.6 ms | 288.8 ms | 421.9 ms | n/a | 688.1 ms | 0.99 cores | kafka 47% · mongo 76% | 0.17 of 1 | server 0.99 | 8.42 | 330.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-base | base | 263.4/s | 263.4/s | 10536 | 0 | 46.8 ms | 161.8 ms | 319.5 ms | n/a | 487.4 ms | 0.99 cores | kafka 48% · mongo 79% | 0.18 of 1 | server 0.99 | 8.59 | 324.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.907 | 0.987 | 1.533 | 2.649 | - | 2.279 | 1.082 | 1.071 |
| 2 | 0.907 | 0.800 | 0.550 | 0.577 | - | 0.503 | 1.112 | 1.102 |
| 3 | 0.907 | 0.915 | 1.213 | 2.251 | - | 2.497 | 1.047 | 1.063 |

Pairs at one rate for both arms, 263.4/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.421 | 0.354 | 0.433 | - | 0.540 | 1.152 | 1.133 |
| 2 | 1.000 | 1.208 | 1.785 | 1.321 | - | 1.412 | 0.980 | 1.020 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@1k | head against base | cost | **more allocation** | head allocates ×1.08 the bytes per operation of base (median of the equal-rate pairs) |
| e3-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -28.8% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.3% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 38.7 ms, p99 610.3 ms |
| e3-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -45.6% (±25.0%) |
| e3-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -36.3% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 33.3 ms, p99 458.8 ms |
| e3-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -85.1% (±25.0%) |
| e3-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +25.4% (±25.0%) |
| e3-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -40.6% (±25.0%) |

