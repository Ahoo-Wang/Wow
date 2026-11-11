## mixed-mongo@100k

Reads and writes together on MongoDB (E1 deposits 50%, E4 point reads 40%, E4 lists 10%): whether the query bulkheads keep the writes fast. Head against baseline.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (61–67 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2044.5/s | 256 | 1226.7/s | 60% |
| head | 1912.1/s | 256 | 1147.3/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1635.6/s | 1491.5/s | 29829 | 2883 | 561.2 ms | 1949.7 ms | 2359.3 ms | n/a | 2588.7 ms | 0.97 cores | kafka 50% · mongo 95% | 0.62 of 1 | server 0.97 | 1.63 | 86.5 KB | 2825 | datasets 8 of 8 · default 1–28 of 50, lowered 6, pressed 17 s | generator-overload 58 |
| probe2-base | base | 1226.7/s | 1216.9/s | 24338 | 196 | 34.8 ms | 181.2 ms | 247.8 ms | n/a | 288.8 ms | 0.94 cores | kafka 47% · mongo 92% | 0.55 of 1 | server 0.94 | 1.92 | 94.2 KB | 196 | datasets 8 of 8 · default 2–17 of 50, lowered 1, pressed 5 s | - |
| probe1-head | head | 1529.7/s | 1432.0/s | 28639 | 1955 | 86.5 ms | 536.6 ms | 745.5 ms | n/a | 864.3 ms | 0.97 cores | kafka 46% · mongo 95% | 0.58 of 1 | server 0.97 | 1.66 | 88.8 KB | 1955 | datasets 8 of 8 · default 1–7 of 50, lowered 5, pressed 15 s | - |
| probe2-head | head | 1147.3/s | 1147.3/s | 22946 | 0 | 22.3 ms | 105.5 ms | 172.0 ms | n/a | 240.6 ms | 0.90 cores | kafka 52% · mongo 91% | 0.52 of 1 | mongo 0.91 | 2.03 | 97.8 KB | - | datasets 8 of 8 · default 8–25 of 50, lowered 0, pressed 0 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1226.7/s | 1226.7/s | 49068 | 0 | 19.2 ms | 98.3 ms | 180.2 ms | n/a | 399.4 ms | 0.79 cores | kafka 49% · mongo 96% | 0.36 of 1 | mongo 0.96 | 1.83 | 97.3 KB | - | datasets 8 of 8 · default 19–25 of 50, lowered 0, pressed 2 s | - |
| r1-head | head | 1147.3/s | 1147.3/s | 45892 | 0 | 17.0 ms | 89.6 ms | 169.0 ms | n/a | 374.8 ms | 0.77 cores | kafka 47% · mongo 96% | 0.34 of 1 | mongo 0.96 | 1.91 | 99.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| r2-head | head | 1147.3/s | 1147.3/s | 45892 | 0 | 23.4 ms | 139.3 ms | 234.5 ms | n/a | 395.3 ms | 0.82 cores | kafka 48% · mongo 97% | 0.33 of 1 | mongo 0.97 | 1.98 | 96.7 KB | - | datasets 8 of 8 · default 20–25 of 50, lowered 0, pressed 9 s | - |
| r2-base | base | 1226.7/s | 1226.7/s | 49068 | 0 | 23.2 ms | 108.5 ms | 173.1 ms | n/a | 329.7 ms | 0.82 cores | kafka 47% · mongo 97% | 0.35 of 1 | mongo 0.97 | 1.84 | 94.5 KB | - | datasets 8 of 8 · default 18–25 of 50, lowered 0, pressed 2 s | - |
| r3-base | base | 1226.7/s | 1226.7/s | 49068 | 0 | 26.0 ms | 121.9 ms | 201.7 ms | n/a | 317.4 ms | 0.84 cores | kafka 46% · mongo 97% | 0.35 of 1 | mongo 0.97 | 1.85 | 93.7 KB | - | datasets 8 of 8 · default 14–25 of 50, lowered 0, pressed 2 s | - |
| r3-head | head | 1147.3/s | 1147.3/s | 45892 | 0 | 21.6 ms | 121.3 ms | 201.7 ms | n/a | 309.2 ms | 0.80 cores | kafka 47% · mongo 97% | 0.34 of 1 | mongo 0.97 | 1.95 | 97.1 KB | - | datasets 8 of 8 · default 22–25 of 50, lowered 0, pressed 3 s | - |
| e1-base | base | 1147.3/s | 1147.3/s | 45892 | 0 | 18.0 ms | 86.5 ms | 141.3 ms | n/a | 238.6 ms | 0.78 cores | kafka 48% · mongo 95% | 0.34 of 1 | mongo 0.95 | 1.92 | 98.0 KB | - | datasets 8 of 8 · default 23–25 of 50, lowered 0, pressed 0 s | - |
| e1-head | head | 1147.3/s | 1147.3/s | 45892 | 0 | 19.2 ms | 98.3 ms | 179.2 ms | n/a | 309.2 ms | 0.78 cores | kafka 46% · mongo 96% | 0.32 of 1 | mongo 0.96 | 1.92 | 98.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e2-head | head | 1147.3/s | 1147.3/s | 45892 | 0 | 22.0 ms | 161.8 ms | 325.6 ms | n/a | 606.2 ms | 0.83 cores | kafka 46% · mongo 94% | 0.34 of 1 | mongo 0.94 | 1.94 | 98.6 KB | - | datasets 8 of 8 · default 24–25 of 50, lowered 0, pressed 8 s | - |
| e2-base | base | 1147.3/s | 1147.3/s | 45892 | 0 | 18.9 ms | 82.4 ms | 138.2 ms | n/a | 259.1 ms | 0.82 cores | kafka 48% · mongo 95% | 0.34 of 1 | mongo 0.95 | 1.96 | 97.7 KB | - | datasets 8 of 8 · default 18–25 of 50, lowered 0, pressed 1 s | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E4-list p50 | E4-list p99 | E4-list failed | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 13.2 ms | 69.1 ms | 0 | 34.8 ms | 227.3 ms | 0 | 12.4 ms | 68.6 ms | 0 |
| r1-head | head | 11.3 ms | 93.7 ms | 0 | 27.3 ms | 209.9 ms | 0 | 10.8 ms | 87.6 ms | 0 |
| r2-head | head | 15.2 ms | 102.9 ms | 0 | 40.7 ms | 272.4 ms | 0 | 14.2 ms | 100.4 ms | 0 |
| r2-base | base | 15.1 ms | 58.1 ms | 0 | 40.2 ms | 213.0 ms | 0 | 14.4 ms | 56.6 ms | 0 |
| r3-base | base | 16.6 ms | 76.3 ms | 0 | 46.3 ms | 233.5 ms | 0 | 15.9 ms | 71.7 ms | 0 |
| r3-head | head | 14.3 ms | 124.9 ms | 0 | 37.9 ms | 231.4 ms | 0 | 13.6 ms | 121.9 ms | 0 |
| e1-base | base | 12.2 ms | 53.8 ms | 0 | 32.0 ms | 161.8 ms | 0 | 11.6 ms | 51.5 ms | 0 |
| e1-head | head | 13.0 ms | 87.0 ms | 0 | 32.4 ms | 214.0 ms | 0 | 12.1 ms | 88.6 ms | 0 |
| e2-head | head | 14.4 ms | 141.3 ms | 0 | 37.6 ms | 372.7 ms | 0 | 13.8 ms | 141.3 ms | 0 |
| e2-base | base | 12.7 ms | 72.7 ms | 0 | 31.5 ms | 162.8 ms | 0 | 12.0 ms | 71.7 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.935 | 0.887 | 0.911 | 0.937 | - | 0.938 | 1.048 | 1.023 |
| 2 | 0.935 | 1.011 | 1.283 | 1.355 | - | 1.199 | 1.074 | 1.023 |
| 3 | 0.935 | 0.833 | 0.996 | 1.000 | - | 0.974 | 1.059 | 1.036 |

Pairs at one rate for both arms, 1147.3/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.064 | 1.136 | 1.268 | - | 1.296 | 1.000 | 1.002 |
| 2 | 1.000 | 1.162 | 1.963 | 2.356 | - | 2.340 | 0.989 | 1.009 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -29.7% (±25.0%) |
| mixed-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 23.4 ms, p99 234.5 ms |
| mixed-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -31.6% (±25.0%) |
| mixed-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -45.6% (±25.0%) |
| mixed-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 22.0 ms, p99 325.6 ms |
| mixed-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -28.7% (±25.0%) |

