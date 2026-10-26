## sustainable-pg@100k

Capacity guidance on PostgreSQL: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (51–53 s), then waited until the nodes were quiet again (4–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 1242.4/s | 1242.4/s | 37271 | 0 | 11.9 ms | 2605.1 ms | 3080.2 ms | n/a | 3178.5 ms | 0.98 cores | kafka 55% · postgres 80% | 0.77 of 1 | server 0.98 | 1.87 | 90.7 KB | - |
| e1-probe2 | head | 621.2/s | 621.2/s | 18635 | 0 | 1.5 ms | 4.8 ms | 11.0 ms | n/a | 57.3 ms | 0.72 cores | kafka 56% · postgres 49% | 0.32 of 1 | server 0.72 | 2.84 | 90.3 KB | - |
| e1-probe3 | head | 931.8/s | 931.8/s | 27954 | 0 | 2.4 ms | 9.4 ms | 19.8 ms | n/a | 64.8 ms | 0.95 cores | kafka 56% · postgres 70% | 0.48 of 1 | server 0.95 | 2.37 | 89.4 KB | - |
| e1-probe4 | head | 1087.1/s | 1087.1/s | 32612 | 0 | 3.9 ms | 18.2 ms | 49.7 ms | n/a | 155.6 ms | 1.00 cores | kafka 50% · postgres 79% | 0.57 of 1 | server 1.00 | 2.10 | 88.5 KB | - |
| e1-probe5 | head | 1164.7/s | 1164.7/s | 34940 | 0 | 4.4 ms | 28.4 ms | 83.5 ms | n/a | 182.3 ms | 1.01 cores | kafka 56% · postgres 79% | 0.61 of 1 | server 1.01 | 2.03 | 88.2 KB | - |
| e3-probe1 | head | 801.2/s | 801.2/s | 24036 | 0 | 20.0 ms | 207.9 ms | 346.1 ms | n/a | 430.1 ms | 1.04 cores | kafka 52% · postgres 112% | 0.43 of 1 | postgres 1.12 | 3.34 | 204.9 KB | - |
| e3-probe2 | head | 400.6/s | 400.6/s | 12018 | 0 | 4.3 ms | 19.3 ms | 34.0 ms | n/a | 73.7 ms | 0.94 cores | kafka 54% · postgres 88% | 0.26 of 1 | server 0.94 | 5.89 | 222.1 KB | - |
| e3-probe3 | head | 600.9/s | 600.9/s | 18027 | 0 | 10.8 ms | 43.3 ms | 81.4 ms | n/a | 234.5 ms | 1.02 cores | kafka 54% · postgres 108% | 0.34 of 1 | postgres 1.08 | 4.39 | 220.7 KB | - |
| e3-probe4 | head | 701.0/s | 701.0/s | 21030 | 0 | 14.6 ms | 75.8 ms | 143.4 ms | n/a | 237.6 ms | 1.05 cores | kafka 58% · postgres 108% | 0.37 of 1 | postgres 1.08 | 3.86 | 211.5 KB | - |
| e3-probe5 | head | 751.1/s | 751.1/s | 22533 | 0 | 17.8 ms | 102.4 ms | 199.7 ms | n/a | 282.6 ms | 1.04 cores | kafka 50% · postgres 111% | 0.41 of 1 | postgres 1.11 | 3.53 | 207.8 KB | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1164.7/s** | 4.4 ms | 83.5 ms | 2.03 | 2484.8/s | 1164.7–1242.4/s |
| E3 | **751.1/s** | 17.8 ms | 199.7 ms | 3.53 | 1602.4/s | 751.1–801.2/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

