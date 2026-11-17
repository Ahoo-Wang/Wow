## sustainable-mongo@100k

Capacity guidance on MongoDB: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: Intel(R) Xeon(R) 6975P-C, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (24–26 s), then waited until the nodes were quiet again (4–5 s).

- `head`: build e3a4e937aa

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 2505.4/s | 2505.4/s | 75162 | 0 | 11.6 ms | 47.6 ms | 86.5 ms | n/a | 151.6 ms | 0.94 cores | kafka 43% · mongo 142% | 0.56 of 1 | mongo 1.42 | 1.11 | 77.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-probe2 | head | 3758.1/s | 3742.5/s | 112276 | 467 | 94.2 ms | 434.2 ms | 626.7 ms | n/a | 835.6 ms | 1.01 cores | kafka 43% · mongo 147% | 0.70 of 1 | mongo 1.47 | 0.78 | 69.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | generator-overload 467 |
| e1-probe3 | head | 3131.7/s | 3131.7/s | 93951 | 0 | 37.4 ms | 165.9 ms | 230.4 ms | n/a | 319.5 ms | 0.97 cores | kafka 43% · mongo 148% | 0.64 of 1 | mongo 1.48 | 0.92 | 72.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e1-probe4 | head | 2818.5/s | 2818.5/s | 84555 | 0 | 16.0 ms | 99.8 ms | 187.4 ms | n/a | 323.6 ms | 0.98 cores | kafka 42% · mongo 143% | 0.61 of 1 | mongo 1.43 | 1.00 | 74.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e1-probe5 | head | 2975.1/s | 2975.1/s | 89253 | 0 | 18.2 ms | 124.9 ms | 198.7 ms | n/a | 284.7 ms | 0.98 cores | kafka 45% · mongo 141% | 0.63 of 1 | mongo 1.41 | 0.95 | 74.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| e2-probe1 | head | 1130.8/s | 1130.8/s | 33924 | 0 | 22.1 ms | 82.4 ms | 122.4 ms | n/a | 172.0 ms | 0.97 cores | kafka 48% · mongo 133% | 0.46 of 1 | mongo 1.33 | 2.46 | 166.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-probe2 | head | 1696.2/s | 1696.2/s | 50885 | 0 | 84.5 ms | 354.3 ms | 475.1 ms | n/a | 671.7 ms | 1.02 cores | kafka 41% · mongo 141% | 0.62 of 1 | mongo 1.41 | 1.67 | 149.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e2-probe3 | head | 1413.5/s | 1413.5/s | 42405 | 0 | 38.1 ms | 207.9 ms | 313.3 ms | n/a | 428.0 ms | 1.00 cores | kafka 42% · mongo 137% | 0.54 of 1 | mongo 1.37 | 1.98 | 156.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e2-probe4 | head | 1272.2/s | 1272.2/s | 38166 | 0 | 28.8 ms | 121.9 ms | 190.5 ms | n/a | 303.1 ms | 1.00 cores | kafka 48% · mongo 132% | 0.50 of 1 | mongo 1.32 | 2.20 | 161.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e2-probe5 | head | 1342.8/s | 1342.8/s | 40284 | 0 | 33.0 ms | 171.0 ms | 251.9 ms | n/a | 342.0 ms | 1.02 cores | kafka 42% · mongo 135% | 0.54 of 1 | mongo 1.35 | 2.07 | 157.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e3-probe1 | head | 733.3/s | 733.3/s | 21999 | 0 | 14.9 ms | 70.7 ms | 110.1 ms | n/a | 162.8 ms | 0.83 cores | kafka 45% · mongo 142% | 0.22 of 1 | mongo 1.42 | 3.68 | 226.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-probe2 | head | 1100.0/s | 1100.0/s | 33000 | 0 | 49.7 ms | 272.4 ms | 360.4 ms | n/a | 446.5 ms | 0.89 cores | kafka 43% · mongo 153% | 0.35 of 1 | mongo 1.53 | 2.60 | 194.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| e3-probe3 | head | 916.6/s | 916.6/s | 27498 | 0 | 21.8 ms | 109.1 ms | 179.2 ms | n/a | 258.0 ms | 0.85 cores | kafka 41% · mongo 151% | 0.32 of 1 | mongo 1.51 | 3.03 | 209.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e3-probe4 | head | 1008.3/s | 1008.3/s | 30249 | 0 | 31.4 ms | 133.1 ms | 173.1 ms | n/a | 213.0 ms | 0.90 cores | kafka 42% · mongo 151% | 0.33 of 1 | mongo 1.51 | 2.80 | 203.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-probe5 | head | 1054.1/s | 1054.1/s | 31622 | 0 | 31.5 ms | 147.5 ms | 215.0 ms | n/a | 290.8 ms | 0.88 cores | kafka 40% · mongo 154% | 0.34 of 1 | mongo 1.54 | 2.67 | 200.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **2975.1/s** | 18.2 ms | 198.7 ms | 0.95 | ≥ 5010.8/s | 2975.1–3131.7/s |
| E2 | **1272.2/s** | 28.8 ms | 190.5 ms | 2.20 | 2261.6/s | 1272.2–1342.8/s |
| E3 | **1008.3/s** | 31.4 ms | 173.1 ms | 2.80 | 1466.6/s | 1008.3–1054.1/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

