## sustainable-pg@100k

Capacity guidance on PostgreSQL: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (41–42 s), then waited until the nodes were quiet again (4–5 s).

- `head`: build e3a4e937aa

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 2237.4/s | 2237.4/s | 67122 | 0 | 18.0 ms | 167.9 ms | 223.2 ms | n/a | 288.8 ms | 1.24 cores | kafka 60% · postgres 88% | 0.79 of 1 | server 1.24 | 1.21 | 73.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| e1-probe2 | head | 1118.7/s | 1118.7/s | 33561 | 0 | 3.4 ms | 10.5 ms | 20.1 ms | n/a | 62.0 ms | 1.07 cores | kafka 62% · postgres 77% | 0.47 of 1 | server 1.07 | 2.20 | 82.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-probe3 | head | 1678.1/s | 1678.1/s | 50343 | 0 | 7.6 ms | 45.8 ms | 127.0 ms | n/a | 209.9 ms | 1.12 cores | kafka 63% · postgres 88% | 0.66 of 1 | server 1.12 | 1.56 | 79.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-probe4 | head | 1957.8/s | 1957.8/s | 58734 | 0 | 11.1 ms | 75.3 ms | 139.3 ms | n/a | 222.2 ms | 1.17 cores | kafka 54% · postgres 90% | 0.70 of 1 | server 1.17 | 1.33 | 76.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-probe5 | head | 2097.6/s | 2097.6/s | 62928 | 0 | 15.5 ms | 167.9 ms | 372.7 ms | n/a | 520.2 ms | 1.19 cores | kafka 62% · postgres 86% | 0.77 of 1 | server 1.19 | 1.28 | 74.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe1 | head | 746.6/s | 746.6/s | 22398 | 0 | 15.7 ms | 50.9 ms | 92.7 ms | n/a | 137.2 ms | 1.14 cores | kafka 58% · postgres 103% | 0.33 of 1 | server 1.14 | 3.68 | 202.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe2 | head | 1119.8/s | 1075.1/s | 32253 | 1341 | 1474.6 ms | 2326.5 ms | 2736.1 ms | n/a | 3227.6 ms | 1.60 cores | kafka 53% · postgres 80% | 0.42 of 1 | server 1.60 | 2.73 | 180.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 20 s | generator-overload 1341 |
| e3-probe3 | head | 933.2/s | 933.2/s | 27996 | 0 | 175.1 ms | 958.5 ms | 1196.0 ms | n/a | 1458.2 ms | 1.50 cores | kafka 53% · postgres 87% | 0.41 of 1 | server 1.50 | 3.11 | 195.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 8 s | - |
| e3-probe4 | head | 839.9/s | 839.9/s | 25197 | 0 | 21.8 ms | 140.3 ms | 190.5 ms | n/a | 244.7 ms | 1.26 cores | kafka 56% · postgres 99% | 0.37 of 1 | server 1.26 | 3.34 | 194.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e3-probe5 | head | 886.5/s | 886.5/s | 26595 | 0 | 59.6 ms | 409.6 ms | 536.6 ms | n/a | 659.5 ms | 1.50 cores | kafka 54% · postgres 86% | 0.40 of 1 | server 1.50 | 3.28 | 188.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1957.8/s** | 11.1 ms | 139.3 ms | 1.33 | 4474.9/s | 1957.8–2097.6/s |
| E3 | **839.9/s** | 21.8 ms | 190.5 ms | 3.34 | 1493.1/s | 839.9–886.5/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

