## sustainable-mongo@100k

Capacity guidance on MongoDB: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (51–56 s), then waited until the nodes were quiet again (4–6 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 1083.1/s | 1083.1/s | 32492 | 0 | 11.6 ms | 64.3 ms | 108.0 ms | n/a | 173.1 ms | 0.82 cores | kafka 53% · mongo 136% | 0.43 of 1 | mongo 1.36 | 2.51 | 117.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-probe2 | head | 1624.6/s | 1624.6/s | 48738 | 0 | 48.1 ms | 411.6 ms | 663.6 ms | n/a | 876.5 ms | 0.94 cores | kafka 53% · mongo 136% | 0.59 of 1 | mongo 1.36 | 1.74 | 101.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e1-probe3 | head | 1353.8/s | 1353.8/s | 40614 | 0 | 19.1 ms | 96.8 ms | 171.0 ms | n/a | 329.7 ms | 0.91 cores | kafka 48% · mongo 141% | 0.54 of 1 | mongo 1.41 | 2.07 | 109.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-probe4 | head | 1489.2/s | 1489.2/s | 44675 | 0 | 22.4 ms | 172.0 ms | 247.8 ms | n/a | 350.2 ms | 0.91 cores | kafka 47% · mongo 143% | 0.57 of 1 | mongo 1.43 | 1.88 | 106.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e1-probe5 | head | 1421.5/s | 1421.5/s | 42645 | 0 | 24.3 ms | 143.4 ms | 219.1 ms | n/a | 299.0 ms | 0.89 cores | kafka 49% · mongo 140% | 0.53 of 1 | mongo 1.40 | 1.96 | 107.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| e2-probe1 | head | 676.7/s | 676.7/s | 20300 | 0 | 29.2 ms | 130.6 ms | 196.6 ms | n/a | 252.9 ms | 0.89 cores | kafka 56% · mongo 127% | 0.49 of 1 | mongo 1.27 | 4.02 | 204.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-probe2 | head | 1015.0/s | 1015.0/s | 30450 | 0 | 146.4 ms | 602.1 ms | 823.3 ms | n/a | 1343.5 ms | 1.01 cores | kafka 47% · mongo 132% | 0.65 of 1 | mongo 1.32 | 2.76 | 181.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| e2-probe3 | head | 845.9/s | 845.9/s | 25377 | 0 | 56.8 ms | 213.0 ms | 288.8 ms | n/a | 362.5 ms | 0.98 cores | kafka 49% · mongo 129% | 0.57 of 1 | mongo 1.29 | 3.27 | 188.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-probe4 | head | 761.3/s | 761.3/s | 22839 | 0 | 29.1 ms | 159.7 ms | 219.1 ms | n/a | 329.7 ms | 0.96 cores | kafka 46% · mongo 132% | 0.53 of 1 | mongo 1.32 | 3.60 | 199.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-probe5 | head | 719.0/s | 719.0/s | 21570 | 0 | 30.5 ms | 190.5 ms | 403.5 ms | n/a | 569.3 ms | 0.91 cores | kafka 50% · mongo 129% | 0.50 of 1 | mongo 1.29 | 3.76 | 200.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e3-probe1 | head | 388.4/s | 388.4/s | 11652 | 0 | 18.2 ms | 82.9 ms | 142.3 ms | n/a | 232.4 ms | 0.75 cores | kafka 55% · mongo 142% | 0.24 of 1 | mongo 1.42 | 6.99 | 331.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-probe2 | head | 582.6/s | 582.6/s | 17478 | 0 | 63.0 ms | 340.0 ms | 462.8 ms | n/a | 692.2 ms | 0.84 cores | kafka 53% · mongo 148% | 0.25 of 1 | mongo 1.48 | 4.89 | 280.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e3-probe3 | head | 485.5/s | 485.5/s | 14565 | 0 | 26.6 ms | 108.5 ms | 161.8 ms | n/a | 227.3 ms | 0.79 cores | kafka 54% · mongo 146% | 0.23 of 1 | mongo 1.46 | 5.75 | 301.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-probe4 | head | 534.0/s | 534.0/s | 16020 | 0 | 32.1 ms | 200.7 ms | 434.2 ms | n/a | 663.6 ms | 0.83 cores | kafka 50% · mongo 149% | 0.25 of 1 | mongo 1.49 | 5.28 | 290.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-probe5 | head | 509.7/s | 509.7/s | 15291 | 0 | 35.1 ms | 153.6 ms | 215.0 ms | n/a | 286.7 ms | 0.81 cores | kafka 52% · mongo 147% | 0.22 of 1 | mongo 1.47 | 5.50 | 297.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1353.8/s** | 19.1 ms | 171.0 ms | 2.07 | 2166.1/s | 1353.8–1421.5/s |
| E2 | **676.7/s** | 29.2 ms | 196.6 ms | 4.02 | 1353.4/s | 676.7–719.0/s |
| E3 | **485.5/s** | 26.6 ms | 161.8 ms | 5.75 | 776.8/s | 485.5–509.7/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

