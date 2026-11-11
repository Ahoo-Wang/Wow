## mixed-pg@1k

Reads and writes together on PostgreSQL, as mixed-mongo.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1379.8/s | 128 | 465.7/s | 34% (**not sustained**) |
| head | 1546.8/s | 256 | 522.0/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1103.8/s | 646.0/s | 12919 | 9157 | 3063.8 ms | 7110.7 ms | 8519.7 ms | n/a | 10158.1 ms | 1.22 cores | kafka 58% · postgres 17% | 0.39 of 2 | server 1.22 | 3.05 | 49.3 KB | 551 | datasets 8 of 8 · default 1–5 of 5, lowered 2, pressed 11 s | generator-overload 8605 · E1:http-504-wow.wait.timeout 1 |
| probe2-base | base | 827.8/s | 635.7/s | 12714 | 3842 | 2056.2 ms | 7110.7 ms | 8716.3 ms | n/a | 10289.2 ms | 1.22 cores | kafka 59% · postgres 16% | 0.40 of 2 | server 1.22 | 3.10 | 62.2 KB | 366 | datasets 8 of 8 · default 1–5 of 5, lowered 0, pressed 6 s | generator-overload 3470 · E1:http-504-wow.wait.timeout 6 |
| probe3-base | base | 620.9/s | 597.3/s | 11945 | 473 | 1204.2 ms | 5439.5 ms | 6586.4 ms | n/a | 8388.6 ms | 1.19 cores | kafka 60% · postgres 17% | 0.41 of 2 | server 1.19 | 3.28 | 83.1 KB | 222 | datasets 8 of 8 · default 1–5 of 5, lowered 0, pressed 11 s | generator-overload 251 |
| probe4-base | base | 465.7/s | 460.8/s | 9216 | 98 | 68.1 ms | 4046.8 ms | 5144.6 ms | n/a | 6586.4 ms | 1.16 cores | kafka 60% · postgres 20% | 0.35 of 2 | server 1.16 | 4.26 | 91.6 KB | 98 | datasets 8 of 8 · default 1–3 of 5, lowered 1, pressed 12 s | - |
| probe1-head | head | 1237.4/s | 579.0/s | 11580 | 13168 | 2654.2 ms | 7995.4 ms | 9568.3 ms | n/a | 11141.1 ms | 1.21 cores | kafka 61% · postgres 15% | 0.40 of 2 | server 1.21 | 3.41 | 39.9 KB | 420 | datasets 8 of 8 · default 1–4 of 5, lowered 1, pressed 12 s | generator-overload 12739 · E1:http-504-wow.wait.timeout 9 |
| probe2-head | head | 928.1/s | 616.4/s | 12327 | 6235 | 2424.8 ms | 7209.0 ms | 8781.8 ms | n/a | 10747.9 ms | 1.23 cores | kafka 57% · postgres 17% | 0.39 of 2 | server 1.23 | 3.19 | 56.2 KB | 460 | datasets 8 of 8 · default 1–5 of 5, lowered 1, pressed 13 s | generator-overload 5767 · E1:http-504-wow.wait.timeout 8 |
| probe3-head | head | 696.0/s | 594.6/s | 11892 | 2028 | 2474.0 ms | 7143.4 ms | 8781.8 ms | n/a | 10223.6 ms | 1.23 cores | kafka 57% · postgres 16% | 0.37 of 2 | server 1.23 | 3.30 | 70.5 KB | 330 | datasets 8 of 8 · default 1–4 of 5, lowered 1, pressed 15 s | generator-overload 1689 · E1:http-504-wow.wait.timeout 9 |
| probe4-head | head | 522.0/s | 498.9/s | 9977 | 463 | 1720.3 ms | 5931.0 ms | 7307.3 ms | n/a | 9371.6 ms | 1.20 cores | kafka 61% · postgres 15% | 0.38 of 2 | server 1.20 | 3.93 | 87.5 KB | 410 | datasets 8 of 8 · default 1–5 of 5, lowered 1, pressed 11 s | generator-overload 53 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 465.7/s | 465.7/s | 18628 | 0 | 6.1 ms | 89.1 ms | 532.5 ms | n/a | 1146.9 ms | 1.15 cores | kafka 57% · postgres 26% | 0.20 of 2 | server 1.15 | 4.25 | 83.1 KB | - | datasets 8 of 8 · default 4 of 5, lowered 0, pressed 2 s | - |
| r1-head | head | 522.0/s | 522.0/s | 20880 | 0 | 8.1 ms | 383.0 ms | 749.6 ms | n/a | 1318.9 ms | 1.15 cores | kafka 56% · postgres 29% | 0.22 of 2 | server 1.15 | 3.82 | 84.8 KB | - | datasets 8 of 8 · default 4–5 of 5, lowered 0, pressed 5 s | - |
| r2-head | head | 522.0/s | 522.0/s | 20880 | 0 | 6.2 ms | 137.2 ms | 305.2 ms | n/a | 589.8 ms | 1.16 cores | kafka 54% · postgres 29% | 0.21 of 2 | server 1.16 | 3.80 | 83.9 KB | - | datasets 8 of 8 · default 3–5 of 5, lowered 0, pressed 0 s | - |
| r2-base | base | 465.7/s | 465.7/s | 18628 | 0 | 6.0 ms | 59.4 ms | 191.5 ms | n/a | 401.4 ms | 1.12 cores | kafka 58% · postgres 27% | 0.20 of 2 | server 1.12 | 4.24 | 81.9 KB | - | datasets 8 of 8 · default 4 of 5, lowered 0, pressed 1 s | - |
| r3-base | base | 465.7/s | 465.7/s | 18628 | 0 | 6.5 ms | 89.6 ms | 227.3 ms | n/a | 1957.9 ms | 1.16 cores | kafka 54% · postgres 27% | 0.20 of 2 | server 1.16 | 4.25 | 82.9 KB | - | datasets 8 of 8 · default 4–5 of 5, lowered 0, pressed 0 s | - |
| r3-head | head | 522.0/s | 522.0/s | 20880 | 0 | 11.2 ms | 222.2 ms | 647.2 ms | n/a | 1384.4 ms | 1.15 cores | kafka 55% · postgres 30% | 0.22 of 2 | server 1.15 | 3.83 | 85.4 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 3 s | - |
| e1-base | base | 465.7/s | 465.7/s | 18628 | 0 | 8.0 ms | 72.7 ms | 393.2 ms | n/a | 1007.6 ms | 1.18 cores | kafka 54% · postgres 28% | 0.19 of 2 | server 1.18 | 4.27 | 83.8 KB | - | datasets 8 of 8 · default 1–3 of 5, lowered 1, pressed 2 s | - |
| e1-head | head | 465.7/s | 465.7/s | 18628 | 0 | 6.7 ms | 64.8 ms | 224.3 ms | n/a | 987.1 ms | 1.17 cores | kafka 54% · postgres 27% | 0.20 of 2 | server 1.17 | 4.26 | 86.1 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |
| e2-head | head | 465.7/s | 465.7/s | 18628 | 0 | 4.9 ms | 33.8 ms | 257.0 ms | n/a | 585.7 ms | 1.13 cores | kafka 58% · postgres 26% | 0.20 of 2 | server 1.13 | 4.25 | 85.2 KB | - | datasets 8 of 8 · default 4 of 5, lowered 0, pressed 1 s | - |
| e2-base | base | 465.7/s | 465.7/s | 18628 | 0 | 4.8 ms | 44.3 ms | 215.0 ms | n/a | 518.1 ms | 1.13 cores | kafka 57% · postgres 26% | 0.19 of 2 | server 1.13 | 4.21 | 82.9 KB | - | datasets 8 of 8 · default 4 of 5, lowered 0, pressed 0 s | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E4-list p50 | E4-list p99 | E4-list failed | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 6.8 ms | 52.7 ms | 0 | 7.3 ms | 675.8 ms | 0 | 4.6 ms | 44.0 ms | 0 |
| r1-head | head | 8.0 ms | 67.1 ms | 0 | 11.7 ms | 880.6 ms | 0 | 6.0 ms | 56.3 ms | 0 |
| r2-head | head | 6.8 ms | 46.3 ms | 0 | 8.0 ms | 360.4 ms | 0 | 4.7 ms | 38.4 ms | 0 |
| r2-base | base | 6.8 ms | 52.2 ms | 0 | 7.0 ms | 240.6 ms | 0 | 4.5 ms | 44.3 ms | 0 |
| r3-base | base | 7.1 ms | 49.2 ms | 0 | 8.1 ms | 276.5 ms | 0 | 4.9 ms | 40.2 ms | 0 |
| r3-head | head | 10.6 ms | 92.7 ms | 0 | 19.1 ms | 876.5 ms | 0 | 7.6 ms | 75.3 ms | 0 |
| e1-base | base | 8.4 ms | 55.6 ms | 0 | 10.6 ms | 514.0 ms | 0 | 6.0 ms | 46.1 ms | 0 |
| e1-head | head | 7.2 ms | 47.6 ms | 0 | 8.4 ms | 317.4 ms | 0 | 5.0 ms | 37.9 ms | 0 |
| e2-head | head | 5.6 ms | 36.6 ms | 0 | 5.8 ms | 329.7 ms | 0 | 3.7 ms | 29.8 ms | 0 |
| e2-base | base | 5.5 ms | 44.3 ms | 0 | 5.7 ms | 288.8 ms | 0 | 3.7 ms | 33.0 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.121 | 1.325 | 4.299 | 1.408 | - | 1.150 | 0.899 | 1.021 |
| 2 | 1.121 | 1.043 | 2.310 | 1.594 | - | 1.469 | 0.897 | 1.024 |
| 3 | 1.121 | 1.716 | 2.480 | 2.847 | - | 0.707 | 0.902 | 1.030 |

Pairs at one rate for both arms, 465.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.829 | 0.891 | 0.570 | - | 0.980 | 0.996 | 1.027 |
| 2 | 1.000 | 1.020 | 0.763 | 1.195 | - | 1.130 | 1.008 | 1.028 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -54.6% (±25.0%) |
| mixed-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 6.1 ms, p99 532.5 ms |
| mixed-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -79.1% (±25.0%) |
| mixed-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 8.1 ms, p99 749.6 ms |
| mixed-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -45.3% (±25.0%) |
| mixed-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 6.2 ms, p99 305.2 ms |
| mixed-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -60.7% (±25.0%) |
| mixed-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 6.0 ms, p99 191.5 ms |
| mixed-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.2% (±25.0%) |
| mixed-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 6.5 ms, p99 227.3 ms |
| mixed-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.8% (±25.0%) |
| mixed-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 11.2 ms, p99 647.2 ms |
| mixed-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.7% (±25.0%) |
| mixed-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 8.0 ms, p99 393.2 ms |
| mixed-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.3% (±25.0%) |
| mixed-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 6.7 ms, p99 224.3 ms |
| mixed-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -38.9% (±25.0%) |
| mixed-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 4.9 ms, p99 257.0 ms |
| mixed-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -40.5% (±25.0%) |
| mixed-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 4.8 ms, p99 215.0 ms |

