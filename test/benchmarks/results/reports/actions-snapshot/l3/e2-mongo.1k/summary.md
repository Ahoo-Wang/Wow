## e2-mongo@1k

E2 read-your-write on MongoDB: head against baseline.

E2, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | ≥ 1223.6/s | 4096 | 413.0/s | 34% (**not sustained**) |
| head | ≥ 1129.0/s | 4096 | 381.0/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 978.9/s | 372.9/s | 7458 | 12120 | 4718.6 ms | 7372.8 ms | 8159.2 ms | n/a | 10616.8 ms | 0.99 cores | kafka 53% · mongo 57% | 0.39 of 1 | server 0.99 | 5.61 | 75.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | generator-overload 12120 |
| probe2-base | base | 734.2/s | 350.9/s | 7018 | 7666 | 5013.5 ms | 8060.9 ms | 9306.1 ms | n/a | 10420.2 ms | 0.99 cores | kafka 52% · mongo 55% | 0.39 of 1 | server 0.99 | 5.89 | 95.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | generator-overload 7666 |
| probe3-base | base | 550.6/s | 388.8/s | 7776 | 3236 | 4653.1 ms | 7012.4 ms | 8028.2 ms | n/a | 9175.0 ms | 0.99 cores | kafka 55% · mongo 58% | 0.40 of 1 | server 0.99 | 5.47 | 133.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 15 s | generator-overload 3236 |
| probe4-base | base | 413.0/s | 355.1/s | 7102 | 1158 | 4816.9 ms | 7405.6 ms | 8388.6 ms | n/a | 9961.5 ms | 0.99 cores | kafka 55% · mongo 58% | 0.42 of 1 | server 0.99 | 5.98 | 171.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | generator-overload 1158 |
| probe1-head | head | 903.2/s | 374.4/s | 7489 | 10575 | 4718.6 ms | 7045.1 ms | 7995.4 ms | n/a | 9437.2 ms | 0.99 cores | kafka 53% · mongo 58% | 0.41 of 1 | server 0.99 | 5.63 | 83.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 15 s | generator-overload 10575 |
| probe2-head | head | 677.4/s | 420.8/s | 8416 | 5132 | 4325.4 ms | 6291.5 ms | 7209.0 ms | n/a | 7897.1 ms | 1.00 cores | kafka 49% · mongo 59% | 0.37 of 1 | server 1.00 | 4.95 | 121.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | generator-overload 5132 |
| probe3-head | head | 508.1/s | 340.3/s | 6806 | 3356 | 5046.3 ms | 7700.5 ms | 9109.5 ms | n/a | 10289.2 ms | 0.99 cores | kafka 53% · mongo 58% | 0.46 of 1 | server 0.99 | 6.18 | 135.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | generator-overload 3356 |
| probe4-head | head | 381.0/s | 350.8/s | 7016 | 604 | 4685.8 ms | 7143.4 ms | 8585.2 ms | n/a | 10289.2 ms | 0.99 cores | kafka 56% · mongo 59% | 0.42 of 1 | server 0.99 | 6.11 | 186.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 18 s | generator-overload 604 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 413.0/s | 412.4/s | 16497 | 23 | 1204.2 ms | 4587.5 ms | 5603.3 ms | n/a | 7045.1 ms | 1.00 cores | kafka 52% · mongo 70% | 0.23 of 1 | server 1.00 | 5.37 | 205.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | generator-overload 23 |
| r1-head | head | 381.0/s | 381.0/s | 15240 | 0 | 109.1 ms | 1802.2 ms | 2424.8 ms | n/a | 3522.6 ms | 1.00 cores | kafka 50% · mongo 74% | 0.21 of 1 | server 1.00 | 5.88 | 218.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r2-head | head | 381.0/s | 381.0/s | 15240 | 0 | 141.3 ms | 1695.7 ms | 2474.0 ms | n/a | 3358.7 ms | 1.00 cores | kafka 48% · mongo 73% | 0.21 of 1 | server 1.00 | 5.79 | 215.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r2-base | base | 413.0/s | 413.0/s | 16520 | 0 | 92.2 ms | 675.8 ms | 1122.3 ms | n/a | 1597.4 ms | 0.99 cores | kafka 49% · mongo 77% | 0.23 of 1 | server 0.99 | 5.45 | 210.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-base | base | 413.0/s | 413.0/s | 16520 | 0 | 142.3 ms | 1835.0 ms | 2424.8 ms | n/a | 3080.2 ms | 1.00 cores | kafka 48% · mongo 74% | 0.22 of 1 | server 1.00 | 5.37 | 209.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r3-head | head | 381.0/s | 381.0/s | 15240 | 0 | 147.5 ms | 2474.0 ms | 3162.1 ms | n/a | 4177.9 ms | 1.00 cores | kafka 49% · mongo 73% | 0.22 of 1 | server 1.00 | 5.82 | 217.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| e1-base | base | 381.0/s | 381.0/s | 15240 | 0 | 102.9 ms | 938.0 ms | 1409.0 ms | n/a | 1867.8 ms | 0.99 cores | kafka 49% · mongo 75% | 0.22 of 1 | server 0.99 | 5.86 | 213.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e1-head | head | 381.0/s | 381.0/s | 15240 | 0 | 122.9 ms | 2097.2 ms | 2785.3 ms | n/a | 3620.9 ms | 1.00 cores | kafka 49% · mongo 74% | 0.22 of 1 | server 1.00 | 5.84 | 217.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e2-head | head | 381.0/s | 381.0/s | 15240 | 0 | 131.1 ms | 2228.2 ms | 2965.5 ms | n/a | 4358.1 ms | 1.00 cores | kafka 48% · mongo 73% | 0.22 of 1 | server 1.00 | 5.79 | 214.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| e2-base | base | 381.0/s | 381.0/s | 15240 | 0 | 126.0 ms | 1679.4 ms | 2342.9 ms | n/a | 2965.5 ms | 1.00 cores | kafka 49% · mongo 75% | 0.21 of 1 | server 1.00 | 5.88 | 211.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.924 | 0.091 | 0.393 | 0.433 | - | 0.500 | 1.095 | 1.065 |
| 2 | 0.923 | 1.533 | 2.509 | 2.204 | - | 2.103 | 1.063 | 1.025 |
| 3 | 0.923 | 1.036 | 1.348 | 1.304 | - | 1.356 | 1.086 | 1.038 |

Pairs at one rate for both arms, 381.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.194 | 2.236 | 1.977 | - | 1.939 | 0.995 | 1.020 |
| 2 | 1.000 | 1.041 | 1.327 | 1.266 | - | 1.470 | 0.985 | 1.018 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e2-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -94.4% (±25.0%) |
| e2-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -64.9% (±25.0%) |
| e2-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 109.1 ms, p99 2424.8 ms |
| e2-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -63.0% (±25.0%) |
| e2-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 141.3 ms, p99 2474.0 ms |
| e2-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -59.6% (±25.0%) |
| e2-mongo@1k | r2-base | steady | **p99 > 10× p50** | p50 92.2 ms, p99 1122.3 ms |
| e2-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.9% (±25.0%) |
| e2-mongo@1k | r3-base | steady | **p99 > 10× p50** | p50 142.3 ms, p99 2424.8 ms |
| e2-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -86.9% (±25.0%) |
| e2-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 147.5 ms, p99 3162.1 ms |
| e2-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.6% (±25.0%) |
| e2-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 102.9 ms, p99 1409.0 ms |
| e2-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -72.1% (±25.0%) |
| e2-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 122.9 ms, p99 2785.3 ms |
| e2-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -84.3% (±25.0%) |
| e2-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 131.1 ms, p99 2965.5 ms |
| e2-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -62.7% (±25.0%) |
| e2-mongo@1k | e2-base | steady | **p99 > 10× p50** | p50 126.0 ms, p99 2342.9 ms |

