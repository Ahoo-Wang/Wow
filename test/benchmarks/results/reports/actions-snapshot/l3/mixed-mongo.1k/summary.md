## mixed-mongo@1k

Reads and writes together on MongoDB (E1 deposits 50%, E4 point reads 40%, E4 lists 10%): whether the query bulkheads keep the writes fast. Head against baseline.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1515.1/s | 512 | 511.4/s | 34% (**not sustained**) |
| head | 1538.4/s | 256 | 519.2/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1212.1/s | 681.3/s | 13625 | 10617 | 2179.1 ms | 6225.9 ms | 7471.1 ms | n/a | 9175.0 ms | 0.99 cores | kafka 50% · mongo 55% | 0.48 of 1 | server 0.99 | 3.00 | 57.0 KB | 1207 | datasets 8 of 8 · default 2–7 of 50, lowered 3, pressed 9 s | generator-overload 9410 |
| probe2-base | base | 909.1/s | 604.1/s | 12083 | 6099 | 2457.6 ms | 6750.2 ms | 8257.5 ms | n/a | 9699.3 ms | 0.99 cores | kafka 55% · mongo 53% | 0.53 of 1 | server 0.99 | 3.43 | 68.7 KB | 1096 | datasets 8 of 8 · default 1–9 of 50, lowered 3, pressed 15 s | generator-overload 5003 |
| probe3-base | base | 681.8/s | 563.2/s | 11264 | 2372 | 2342.9 ms | 7569.4 ms | 9044.0 ms | n/a | 10485.8 ms | 0.99 cores | kafka 51% · mongo 53% | 0.42 of 1 | server 0.99 | 3.61 | 86.1 KB | 812 | datasets 8 of 8 · default 2–7 of 50, lowered 2, pressed 10 s | generator-overload 1550 · E1:http-504-wow.wait.timeout 10 |
| probe4-base | base | 511.4/s | 479.8/s | 9595 | 633 | 749.6 ms | 5439.5 ms | 6881.3 ms | n/a | 8093.7 ms | 0.99 cores | kafka 58% · mongo 56% | 0.51 of 1 | server 0.99 | 4.44 | 114.4 KB | 633 | datasets 8 of 8 · default 1–5 of 50, lowered 3, pressed 10 s | - |
| probe1-head | head | 1230.7/s | 700.5/s | 14009 | 10605 | 2326.5 ms | 5996.5 ms | 7569.4 ms | n/a | 9830.4 ms | 0.99 cores | kafka 53% · mongo 58% | 0.51 of 1 | server 0.99 | 3.01 | 59.1 KB | 1221 | datasets 8 of 8 · default 1–7 of 50, lowered 3, pressed 13 s | generator-overload 9383 · E1:http-504-wow.wait.timeout 1 |
| probe2-head | head | 923.0/s | 691.0/s | 13819 | 4641 | 2293.8 ms | 5898.2 ms | 7274.5 ms | n/a | 8454.1 ms | 0.99 cores | kafka 55% · mongo 56% | 0.52 of 1 | server 0.99 | 3.04 | 77.8 KB | 1150 | datasets 8 of 8 · default 1–10 of 50, lowered 3, pressed 12 s | generator-overload 3491 |
| probe3-head | head | 692.3/s | 624.9/s | 12498 | 1348 | 1187.8 ms | 5308.4 ms | 6651.9 ms | n/a | 8781.8 ms | 1.00 cores | kafka 54% · mongo 58% | 0.48 of 1 | server 1.00 | 3.38 | 101.9 KB | 1033 | datasets 8 of 8 · default 1–5 of 50, lowered 4, pressed 15 s | generator-overload 315 |
| probe4-head | head | 519.2/s | 503.9/s | 10079 | 305 | 80.9 ms | 3145.7 ms | 4325.4 ms | n/a | 5570.6 ms | 0.99 cores | kafka 56% · mongo 64% | 0.40 of 1 | server 0.99 | 4.35 | 122.3 KB | 305 | datasets 8 of 8 · default 1–11 of 50, lowered 2, pressed 6 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 511.4/s | 511.4/s | 20456 | 0 | 9.0 ms | 43.8 ms | 88.6 ms | n/a | 188.4 ms | 0.99 cores | kafka 58% · mongo 77% | 0.22 of 1 | server 0.99 | 4.56 | 128.6 KB | - | datasets 8 of 8 · default 18–25 of 50, lowered 0, pressed 0 s | - |
| r1-head | head | 519.2/s | 519.2/s | 20768 | 0 | 20.6 ms | 111.1 ms | 292.9 ms | n/a | 602.1 ms | 0.99 cores | kafka 55% · mongo 75% | 0.26 of 1 | server 0.99 | 4.43 | 122.3 KB | - | datasets 8 of 8 · default 11–25 of 50, lowered 0, pressed 1 s | - |
| r2-head | head | 519.2/s | 519.2/s | 20768 | 0 | 21.5 ms | 101.9 ms | 182.3 ms | n/a | 319.5 ms | 0.99 cores | kafka 53% · mongo 73% | 0.25 of 1 | server 0.99 | 4.35 | 123.6 KB | - | datasets 8 of 8 · default 7–25 of 50, lowered 0, pressed 0 s | - |
| r2-base | base | 511.4/s | 511.4/s | 20456 | 0 | 15.0 ms | 57.6 ms | 96.8 ms | n/a | 184.3 ms | 0.99 cores | kafka 55% · mongo 75% | 0.27 of 1 | server 0.99 | 4.48 | 125.0 KB | - | datasets 8 of 8 · default 10–25 of 50, lowered 0, pressed 0 s | - |
| r3-base | base | 511.4/s | 511.4/s | 20456 | 0 | 15.8 ms | 60.9 ms | 102.4 ms | n/a | 151.6 ms | 0.99 cores | kafka 57% · mongo 75% | 0.23 of 1 | server 0.99 | 4.52 | 124.9 KB | - | datasets 8 of 8 · default 6–25 of 50, lowered 0, pressed 1 s | - |
| r3-head | head | 519.2/s | 519.2/s | 20768 | 0 | 18.3 ms | 80.9 ms | 145.4 ms | n/a | 378.9 ms | 0.99 cores | kafka 55% · mongo 75% | 0.24 of 1 | server 0.99 | 4.42 | 126.8 KB | - | datasets 8 of 8 · default 11–25 of 50, lowered 0, pressed 1 s | - |
| e1-base | base | 511.4/s | 511.4/s | 20456 | 0 | 10.1 ms | 46.6 ms | 83.5 ms | n/a | 143.4 ms | 0.99 cores | kafka 56% · mongo 76% | 0.25 of 1 | server 0.99 | 4.53 | 126.7 KB | - | datasets 8 of 8 · default 14–25 of 50, lowered 0, pressed 0 s | - |
| e1-head | head | 511.4/s | 511.4/s | 20456 | 0 | 20.7 ms | 106.5 ms | 282.6 ms | n/a | 606.2 ms | 0.99 cores | kafka 54% · mongo 75% | 0.23 of 1 | server 0.99 | 4.45 | 125.8 KB | - | datasets 8 of 8 · default 5–25 of 50, lowered 1, pressed 2 s | - |
| e2-head | head | 511.4/s | 511.4/s | 20456 | 0 | 16.0 ms | 68.1 ms | 126.0 ms | n/a | 236.5 ms | 0.99 cores | kafka 55% · mongo 76% | 0.25 of 1 | server 0.99 | 4.49 | 126.5 KB | - | datasets 8 of 8 · default 7–25 of 50, lowered 0, pressed 0 s | - |
| e2-base | base | 511.4/s | 511.4/s | 20456 | 0 | 9.9 ms | 40.2 ms | 65.5 ms | n/a | 170.0 ms | 0.99 cores | kafka 56% · mongo 76% | 0.25 of 1 | server 0.99 | 4.53 | 127.5 KB | - | datasets 8 of 8 · default 12–25 of 50, lowered 0, pressed 0 s | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E4-list p50 | E4-list p99 | E4-list failed | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 7.3 ms | 67.1 ms | 0 | 13.2 ms | 116.7 ms | 0 | 6.3 ms | 68.6 ms | 0 |
| r1-head | head | 15.4 ms | 136.2 ms | 0 | 28.2 ms | 348.2 ms | 0 | 15.4 ms | 171.0 ms | 0 |
| r2-head | head | 15.8 ms | 127.0 ms | 0 | 29.2 ms | 206.8 ms | 0 | 15.8 ms | 117.2 ms | 0 |
| r2-base | base | 11.8 ms | 63.0 ms | 0 | 20.2 ms | 115.7 ms | 0 | 11.2 ms | 63.5 ms | 0 |
| r3-base | base | 12.4 ms | 70.1 ms | 0 | 21.2 ms | 116.7 ms | 0 | 11.8 ms | 72.7 ms | 0 |
| r3-head | head | 13.8 ms | 95.7 ms | 0 | 24.8 ms | 178.2 ms | 0 | 13.4 ms | 104.4 ms | 0 |
| e1-base | base | 8.0 ms | 52.7 ms | 0 | 14.3 ms | 95.7 ms | 0 | 7.4 ms | 56.6 ms | 0 |
| e1-head | head | 16.1 ms | 190.5 ms | 0 | 26.9 ms | 337.9 ms | 0 | 15.8 ms | 176.1 ms | 0 |
| e2-head | head | 12.1 ms | 99.3 ms | 0 | 22.5 ms | 143.4 ms | 0 | 11.6 ms | 92.2 ms | 0 |
| e2-base | base | 7.6 ms | 46.8 ms | 0 | 13.8 ms | 71.7 ms | 0 | 7.2 ms | 50.4 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.015 | 2.284 | 2.538 | 3.306 | - | 3.196 | 0.971 | 0.951 |
| 2 | 1.015 | 1.436 | 1.769 | 1.884 | - | 1.733 | 0.970 | 0.989 |
| 3 | 1.015 | 1.158 | 1.328 | 1.420 | - | 2.500 | 0.978 | 1.016 |

Pairs at one rate for both arms, 511.4/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 2.051 | 2.286 | 3.387 | - | 4.229 | 0.982 | 0.993 |
| 2 | 1.000 | 1.623 | 1.694 | 1.922 | - | 1.392 | 0.991 | 0.992 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -33.1% (±25.0%) |
| mixed-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -52.5% (±25.0%) |
| mixed-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 20.6 ms, p99 292.9 ms |
| mixed-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -64.8% (±25.0%) |
| mixed-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -39.3% (±25.0%) |
| mixed-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.9% (±25.0%) |
| mixed-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -60.7% (±25.0%) |
| mixed-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -66.2% (±25.0%) |
| mixed-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -47.2% (±25.0%) |
| mixed-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 20.7 ms, p99 282.6 ms |
| mixed-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -46.9% (±25.0%) |

