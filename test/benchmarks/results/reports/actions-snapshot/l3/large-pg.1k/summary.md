## large-pg@1k

E1 with a large payload on PostgreSQL, as large-mongo.

E1-large, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 891.9/s | 2048 | 301.0/s | 34% |
| head | 974.9/s | 2048 | 329.0/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 713.5/s | 365.3/s | 7306 | 6964 | 4980.7 ms | 7864.3 ms | 9437.2 ms | n/a | 10354.7 ms | 1.03 cores | kafka 63% · postgres 33% | 0.42 of 2 | server 1.03 | 5.45 | 128.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | generator-overload 6964 |
| probe2-base | base | 535.1/s | 387.1/s | 7743 | 2959 | 4587.5 ms | 7340.0 ms | 8716.3 ms | n/a | 10551.3 ms | 1.02 cores | kafka 64% · postgres 36% | 0.41 of 2 | server 1.02 | 5.22 | 180.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | generator-overload 2959 |
| probe3-base | base | 401.3/s | 365.0/s | 7300 | 726 | 4554.8 ms | 7176.2 ms | 8290.3 ms | n/a | 9699.3 ms | 1.02 cores | kafka 62% · postgres 36% | 0.37 of 2 | server 1.02 | 5.48 | 225.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | generator-overload 725 · http-504-wow.wait.timeout 1 |
| probe4-base | base | 301.0/s | 301.0/s | 6020 | 0 | 3162.1 ms | 5996.5 ms | 7209.0 ms | n/a | 8323.1 ms | 1.01 cores | kafka 65% · postgres 35% | 0.38 of 2 | server 1.01 | 6.69 | 299.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| probe1-head | head | 779.9/s | 362.1/s | 7242 | 8356 | 4915.2 ms | 7634.9 ms | 9240.6 ms | n/a | 10158.1 ms | 1.03 cores | kafka 61% · postgres 35% | 0.43 of 2 | server 1.03 | 5.50 | 117.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | generator-overload 8350 · http-504-wow.wait.timeout 6 |
| probe2-head | head | 584.9/s | 365.5/s | 7310 | 4388 | 4522.0 ms | 7766.0 ms | 9306.1 ms | n/a | 10551.3 ms | 1.05 cores | kafka 62% · postgres 35% | 0.41 of 2 | server 1.05 | 5.51 | 158.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | generator-overload 4155 · http-504-wow.wait.timeout 233 |
| probe3-head | head | 438.7/s | 353.1/s | 7063 | 1711 | 5210.1 ms | 8060.9 ms | 9502.7 ms | n/a | 10944.5 ms | 1.05 cores | kafka 63% · postgres 32% | 0.43 of 2 | server 1.05 | 5.63 | 198.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | generator-overload 1701 · http-504-wow.wait.timeout 10 |
| probe4-head | head | 329.0/s | 319.1/s | 6383 | 197 | 3915.8 ms | 7307.3 ms | 9764.9 ms | n/a | 10682.4 ms | 1.02 cores | kafka 65% · postgres 34% | 0.36 of 2 | server 1.02 | 6.32 | 280.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | generator-overload 152 · http-504-wow.wait.timeout 45 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 301.0/s | 301.0/s | 12040 | 0 | 23.3 ms | 272.4 ms | 477.2 ms | n/a | 831.5 ms | 1.00 cores | kafka 53% · postgres 46% | 0.20 of 2 | server 1.00 | 6.64 | 256.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r1-head | head | 329.0/s | 329.0/s | 13160 | 0 | 113.2 ms | 3309.6 ms | 4390.9 ms | n/a | 5668.9 ms | 0.96 cores | kafka 56% · postgres 48% | 0.22 of 2 | server 0.96 | 6.10 | 273.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 11 s | - |
| r2-head | head | 329.0/s | 329.0/s | 13160 | 0 | 116.2 ms | 2179.1 ms | 3162.1 ms | n/a | 4259.8 ms | 0.95 cores | kafka 57% · postgres 49% | 0.20 of 2 | server 0.95 | 6.12 | 268.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 8 s | - |
| r2-base | base | 301.0/s | 301.0/s | 12040 | 0 | 23.2 ms | 135.2 ms | 214.0 ms | n/a | 370.7 ms | 1.02 cores | kafka 51% · postgres 47% | 0.18 of 2 | server 1.02 | 6.63 | 256.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r3-base | base | 301.0/s | 301.0/s | 12040 | 0 | 142.3 ms | 2342.9 ms | 3326.0 ms | n/a | 4423.7 ms | 0.97 cores | kafka 58% · postgres 46% | 0.20 of 2 | server 0.97 | 6.67 | 269.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | - |
| r3-head | head | 329.0/s | 329.0/s | 13160 | 0 | 786.4 ms | 5570.6 ms | 6914.0 ms | n/a | 8781.8 ms | 0.98 cores | kafka 60% · postgres 42% | 0.22 of 2 | server 0.98 | 6.09 | 272.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 15 s | - |
| e1-base | base | 301.0/s | 301.0/s | 12040 | 0 | 126.0 ms | 1400.8 ms | 2244.6 ms | n/a | 3047.4 ms | 0.97 cores | kafka 57% · postgres 46% | 0.19 of 2 | server 0.97 | 6.65 | 263.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 10 s | - |
| e1-head | head | 301.0/s | 301.0/s | 12040 | 0 | 115.7 ms | 2572.3 ms | 3719.2 ms | n/a | 5275.6 ms | 0.99 cores | kafka 55% · postgres 46% | 0.21 of 2 | server 0.99 | 6.66 | 272.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | - |
| e2-head | head | 301.0/s | 301.0/s | 12040 | 0 | 52.5 ms | 2015.2 ms | 3031.0 ms | n/a | 4194.3 ms | 0.97 cores | kafka 56% · postgres 48% | 0.19 of 2 | server 0.97 | 6.68 | 269.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | - |
| e2-base | base | 301.0/s | 301.0/s | 12040 | 0 | 110.6 ms | 2506.8 ms | 3457.0 ms | n/a | 5111.8 ms | 0.97 cores | kafka 57% · postgres 46% | 0.20 of 2 | server 0.97 | 6.65 | 266.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.093 | 4.857 | 12.150 | 9.202 | - | 6.818 | 0.919 | 1.064 |
| 2 | 1.093 | 5.017 | 16.121 | 14.775 | - | 11.492 | 0.922 | 1.043 |
| 3 | 1.093 | 5.525 | 2.378 | 2.079 | - | 1.985 | 0.914 | 1.014 |

Pairs at one rate for both arms, 301.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.919 | 1.836 | 1.657 | - | 1.731 | 1.002 | 1.037 |
| 2 | 1.000 | 0.475 | 0.804 | 0.877 | - | 0.821 | 1.003 | 1.011 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -64.7% (±25.0%) |
| large-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 23.3 ms, p99 477.2 ms |
| large-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -94.1% (±25.0%) |
| large-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 113.2 ms, p99 4390.9 ms |
| large-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -74.5% (±25.0%) |
| large-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 116.2 ms, p99 3162.1 ms |
| large-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -80.1% (±25.0%) |
| large-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -79.3% (±25.0%) |
| large-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 142.3 ms, p99 3326.0 ms |
| large-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -94.1% (±25.0%) |
| large-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -76.1% (±25.0%) |
| large-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 126.0 ms, p99 2244.6 ms |
| large-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -87.5% (±25.0%) |
| large-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 115.7 ms, p99 3719.2 ms |
| large-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -83.9% (±25.0%) |
| large-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 52.5 ms, p99 3031.0 ms |
| large-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -87.1% (±25.0%) |
| large-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 110.6 ms, p99 3457.0 ms |

