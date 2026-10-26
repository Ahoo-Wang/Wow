## sustainable-mongo@100k

Capacity guidance on MongoDB: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (55–59 s), then waited until the nodes were quiet again (3–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 1023.8/s | 1023.8/s | 30714 | 0 | 8.6 ms | 72.2 ms | 161.8 ms | n/a | 290.8 ms | 0.80 cores | kafka 49% · mongo 141% | 0.53 of 1 | mongo 1.41 | 2.63 | 116.8 KB | - |
| e1-probe2 | head | 1535.6/s | 1474.2/s | 44226 | 1842 | 12451.8 ms | 13893.6 ms | 15794.2 ms | n/a | 16449.5 ms | 0.80 cores | kafka 50% · mongo 127% | 0.95 of 1 | mongo 1.27 | 1.74 | 90.3 KB | generator-overload 1842 |
| e1-probe3 | head | 1279.7/s | 1279.7/s | 38391 | 0 | 1019.9 ms | 4079.6 ms | 4423.7 ms | n/a | 4516.2 ms | 0.82 cores | kafka 50% · mongo 133% | 0.86 of 1 | mongo 1.33 | 2.07 | 107.2 KB | - |
| e1-probe4 | head | 1151.7/s | 1151.7/s | 34550 | 0 | 12.9 ms | 60.7 ms | 99.3 ms | n/a | 344.1 ms | 0.83 cores | kafka 49% · mongo 141% | 0.60 of 1 | mongo 1.41 | 2.37 | 109.0 KB | - |
| e1-probe5 | head | 1215.7/s | 1215.7/s | 36471 | 0 | 1011.7 ms | 4849.7 ms | 5505.0 ms | n/a | 5636.1 ms | 0.83 cores | kafka 49% · mongo 133% | 0.85 of 1 | mongo 1.33 | 2.18 | 110.8 KB | - |
| e2-probe1 | head | 547.4/s | 547.4/s | 16422 | 0 | 14.1 ms | 95.7 ms | 191.5 ms | n/a | 294.9 ms | 0.79 cores | kafka 54% · mongo 127% | 0.53 of 1 | mongo 1.27 | 4.74 | 204.4 KB | - |
| e2-probe2 | head | 821.1/s | 821.1/s | 24633 | 0 | 17956.9 ms | 19267.6 ms | 21102.6 ms | n/a | 22406.3 ms | 0.77 cores | kafka 49% · mongo 114% | 0.94 of 1 | mongo 1.14 | 2.91 | 170.4 KB | - |
| e2-probe3 | head | 684.2/s | 684.2/s | 20525 | 0 | 101.9 ms | 4014.1 ms | 4587.5 ms | n/a | 5079.0 ms | 0.86 cores | kafka 51% · mongo 123% | 0.77 of 1 | mongo 1.23 | 3.80 | 198.9 KB | - |
| e2-probe4 | head | 615.8/s | 615.8/s | 18474 | 0 | 30.7 ms | 2129.9 ms | 2588.7 ms | n/a | 2768.9 ms | 0.81 cores | kafka 47% · mongo 126% | 0.66 of 1 | mongo 1.26 | 4.13 | 201.2 KB | - |
| e2-probe5 | head | 581.6/s | 581.6/s | 17448 | 0 | 25.2 ms | 356.4 ms | 1056.8 ms | n/a | 1171.5 ms | 0.83 cores | kafka 52% · mongo 126% | 0.61 of 1 | mongo 1.26 | 4.50 | 199.7 KB | - |
| e3-probe1 | head | 446.6/s | 446.6/s | 13397 | 0 | 16.8 ms | 195.6 ms | 288.8 ms | n/a | 385.0 ms | 0.76 cores | kafka 49% · mongo 152% | 0.28 of 1 | mongo 1.52 | 6.20 | 285.6 KB | - |
| e3-probe2 | head | 223.3/s | 223.3/s | 6698 | 0 | 6.5 ms | 37.6 ms | 93.7 ms | n/a | 192.5 ms | 0.64 cores | kafka 53% · mongo 129% | 0.22 of 1 | mongo 1.29 | 11.01 | 367.2 KB | - |
| e3-probe3 | head | 335.0/s | 335.0/s | 10050 | 0 | 12.4 ms | 77.8 ms | 198.7 ms | n/a | 333.8 ms | 0.74 cores | kafka 52% · mongo 148% | 0.26 of 1 | mongo 1.48 | 8.15 | 326.7 KB | - |
| e3-probe4 | head | 390.8/s | 390.8/s | 11724 | 0 | 14.6 ms | 107.0 ms | 214.0 ms | n/a | 479.2 ms | 0.74 cores | kafka 49% · mongo 150% | 0.28 of 1 | mongo 1.50 | 6.98 | 305.5 KB | - |
| e3-probe5 | head | 362.9/s | 362.9/s | 10887 | 0 | 13.4 ms | 88.6 ms | 152.6 ms | n/a | 215.0 ms | 0.75 cores | kafka 50% · mongo 149% | 0.30 of 1 | mongo 1.49 | 7.54 | 313.6 KB | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1151.7/s** | 12.9 ms | 99.3 ms | 2.37 | 2047.5/s | 1151.7–1215.7/s |
| E2 | **547.4/s** | 14.1 ms | 191.5 ms | 4.74 | 1094.8/s | 547.4–581.6/s |
| E3 | **362.9/s** | 13.4 ms | 152.6 ms | 7.54 | 893.3/s | 362.9–390.8/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

