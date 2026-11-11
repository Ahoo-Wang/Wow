## e3-pg@1k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 578.4/s | 1024 | 195.2/s | 34% |
| head | 583.8/s | 2048 | 197.0/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 462.7/s | 232.2/s | 4644 | 4610 | 6225.9 ms | 9895.9 ms | 10616.8 ms | n/a | 11051.5 ms | 1.13 cores | kafka 66% · postgres 21% | 0.32 of 2 | server 1.13 | 8.60 | 135.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 14 s | generator-overload 3931 · http-504-wow.wait.timeout 679 |
| probe2-base | base | 347.0/s | 186.8/s | 3736 | 3204 | 5931.0 ms | 9961.5 ms | 10551.3 ms | n/a | 11141.1 ms | 1.12 cores | kafka 67% · postgres 20% | 0.29 of 2 | server 1.12 | 10.67 | 159.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | generator-overload 1871 · http-504-wow.wait.timeout 1333 |
| probe3-base | base | 260.3/s | 194.4/s | 3888 | 1318 | 6488.1 ms | 10027.0 ms | 10747.9 ms | n/a | 11206.7 ms | 1.14 cores | kafka 65% · postgres 20% | 0.29 of 2 | server 1.14 | 10.25 | 218.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 11 s | generator-overload 723 · http-504-wow.wait.timeout 595 |
| probe4-base | base | 195.2/s | 194.9/s | 3899 | 5 | 3883.0 ms | 7176.2 ms | 8716.3 ms | n/a | 9830.4 ms | 1.13 cores | kafka 67% · postgres 22% | 0.28 of 2 | server 1.13 | 10.40 | 294.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | http-504-wow.wait.timeout 5 |
| probe1-head | head | 467.0/s | 203.9/s | 4079 | 5261 | 6848.5 ms | 10158.1 ms | 10813.4 ms | n/a | 11610.5 ms | 1.15 cores | kafka 63% · postgres 20% | 0.29 of 2 | server 1.15 | 9.71 | 124.3 KB | - | datasets 8 of 8 · default 2–5 of 5, lowered 1, pressed 10 s | generator-overload 4372 · http-504-wow.wait.timeout 889 |
| probe2-head | head | 350.3/s | 185.0/s | 3700 | 3306 | 6651.9 ms | 10158.1 ms | 10747.9 ms | n/a | 11272.2 ms | 1.17 cores | kafka 61% · postgres 20% | 0.29 of 2 | server 1.17 | 10.73 | 163.2 KB | - | datasets 8 of 8 · default 1–3 of 5, lowered 2, pressed 11 s | generator-overload 2199 · http-504-wow.wait.timeout 1107 |
| probe3-head | head | 262.7/s | 193.2/s | 3864 | 1390 | 6455.3 ms | 10092.5 ms | 10682.4 ms | n/a | 11373.0 ms | 1.16 cores | kafka 63% · postgres 21% | 0.25 of 2 | server 1.16 | 10.35 | 225.9 KB | - | datasets 8 of 8 · default 1–4 of 5, lowered 2, pressed 14 s | http-504-wow.wait.timeout 757 · generator-overload 633 |
| probe4-head | head | 197.0/s | 196.3/s | 3925 | 15 | 4489.2 ms | 7897.1 ms | 9633.8 ms | n/a | 10616.8 ms | 1.15 cores | kafka 66% · postgres 22% | 0.25 of 2 | server 1.15 | 10.32 | 305.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | http-504-wow.wait.timeout 15 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 195.2/s | 195.2/s | 7808 | 0 | 88.6 ms | 1941.5 ms | 2703.4 ms | n/a | 4112.4 ms | 1.01 cores | kafka 53% · postgres 46% | 0.20 of 2 | server 1.01 | 10.26 | 286.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| r1-head | head | 197.0/s | 197.0/s | 7880 | 0 | 228.4 ms | 2326.5 ms | 3309.6 ms | n/a | 4685.8 ms | 1.03 cores | kafka 54% · postgres 43% | 0.19 of 2 | server 1.03 | 10.16 | 297.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r2-head | head | 197.0/s | 197.0/s | 7880 | 0 | 89.1 ms | 1081.3 ms | 1482.8 ms | n/a | 2146.3 ms | 1.02 cores | kafka 56% · postgres 45% | 0.18 of 2 | server 1.02 | 10.27 | 288.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| r2-base | base | 195.2/s | 195.2/s | 7808 | 0 | 45.8 ms | 581.6 ms | 819.2 ms | n/a | 1261.6 ms | 1.00 cores | kafka 55% · postgres 46% | 0.18 of 2 | server 1.00 | 10.30 | 270.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| r3-base | base | 195.2/s | 195.2/s | 7808 | 0 | 68.6 ms | 757.8 ms | 1032.2 ms | n/a | 1679.4 ms | 1.01 cores | kafka 56% · postgres 45% | 0.18 of 2 | server 1.01 | 10.34 | 271.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r3-head | head | 197.0/s | 197.0/s | 7880 | 0 | 68.1 ms | 966.7 ms | 1351.7 ms | n/a | 1916.9 ms | 1.01 cores | kafka 55% · postgres 46% | 0.18 of 2 | server 1.01 | 10.26 | 291.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e1-base | base | 195.2/s | 195.2/s | 7808 | 0 | 43.3 ms | 333.8 ms | 499.7 ms | n/a | 749.6 ms | 1.00 cores | kafka 55% · postgres 47% | 0.17 of 2 | server 1.00 | 10.34 | 271.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-head | head | 195.2/s | 195.2/s | 7808 | 0 | 64.0 ms | 358.4 ms | 491.5 ms | n/a | 630.8 ms | 1.01 cores | kafka 54% · postgres 46% | 0.18 of 2 | server 1.01 | 10.35 | 284.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e2-head | head | 195.2/s | 195.2/s | 7808 | 0 | 106.5 ms | 1409.0 ms | 1974.3 ms | n/a | 2736.1 ms | 1.02 cores | kafka 55% · postgres 45% | 0.21 of 2 | server 1.02 | 10.35 | 292.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| e2-base | base | 195.2/s | 195.2/s | 7808 | 0 | 40.4 ms | 255.0 ms | 393.2 ms | n/a | 802.8 ms | 0.99 cores | kafka 55% · postgres 47% | 0.19 of 2 | server 0.99 | 10.31 | 273.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.009 | 2.578 | 1.198 | 1.224 | - | 1.139 | 0.990 | 1.036 |
| 2 | 1.009 | 1.944 | 1.859 | 1.810 | - | 1.701 | 0.997 | 1.067 |
| 3 | 1.009 | 0.993 | 1.276 | 1.310 | - | 1.141 | 0.992 | 1.076 |

Pairs at one rate for both arms, 195.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.479 | 1.074 | 0.984 | - | 0.842 | 1.001 | 1.050 |
| 2 | 1.000 | 2.633 | 5.526 | 5.021 | - | 3.408 | 1.004 | 1.068 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@1k | head against base | cost | **more allocation** | head allocates ×1.06 the bytes per operation of base (median of the equal-rate pairs) |
| e3-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -78.0% (±25.0%) |
| e3-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 88.6 ms, p99 2703.4 ms |
| e3-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -64.7% (±25.0%) |
| e3-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 228.4 ms, p99 3309.6 ms |
| e3-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -91.9% (±25.0%) |
| e3-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 89.1 ms, p99 1482.8 ms |
| e3-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -75.4% (±25.0%) |
| e3-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 45.8 ms, p99 819.2 ms |
| e3-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -76.1% (±25.0%) |
| e3-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 68.6 ms, p99 1032.2 ms |
| e3-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -78.0% (±25.0%) |
| e3-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 68.1 ms, p99 1351.7 ms |
| e3-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.4% (±25.0%) |
| e3-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 43.3 ms, p99 499.7 ms |
| e3-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -81.3% (±25.0%) |
| e3-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.0% (±25.0%) |
| e3-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 106.5 ms, p99 1974.3 ms |
| e3-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -79.3% (±25.0%) |

