## sustainable-pg@100k

Capacity guidance on PostgreSQL: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (23–30 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 2456.3/s | 2456.3/s | 73689 | 0 | 5.2 ms | 74.2 ms | 186.4 ms | n/a | 292.9 ms | 1.13 cores | kafka 52% · postgres 96% | 0.59 of 1 | server 1.13 | 1.06 | 96.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-probe2 | head | 3684.4/s | 3684.4/s | 110531 | 0 | 66.0 ms | 272.4 ms | 356.4 ms | n/a | 446.5 ms | 1.18 cores | kafka 51% · postgres 92% | 0.76 of 1 | server 1.18 | 0.71 | 88.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |
| e1-probe3 | head | 3070.3/s | 3070.3/s | 92109 | 0 | 12.5 ms | 128.0 ms | 344.1 ms | n/a | 516.1 ms | 1.14 cores | kafka 51% · postgres 89% | 0.66 of 1 | server 1.14 | 0.83 | 92.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e1-probe4 | head | 2763.3/s | 2763.3/s | 82898 | 0 | 11.6 ms | 89.1 ms | 224.3 ms | n/a | 438.3 ms | 1.05 cores | kafka 52% · postgres 92% | 0.62 of 1 | server 1.05 | 0.90 | 92.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e1-probe5 | head | 2609.8/s | 2609.8/s | 78293 | 0 | 4.6 ms | 48.1 ms | 160.8 ms | n/a | 325.6 ms | 1.11 cores | kafka 48% · postgres 94% | 0.59 of 1 | server 1.11 | 0.97 | 97.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe1 | head | 1187.0/s | 1187.0/s | 35610 | 0 | 39.7 ms | 193.5 ms | 483.3 ms | n/a | 630.8 ms | 1.10 cores | kafka 51% · postgres 105% | 0.36 of 1 | server 1.10 | 2.24 | 236.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e3-probe2 | head | 593.5/s | 593.5/s | 17805 | 0 | 4.0 ms | 66.6 ms | 152.6 ms | n/a | 290.8 ms | 0.89 cores | kafka 47% · postgres 75% | 0.16 of 1 | server 0.89 | 3.57 | 258.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe3 | head | 890.3/s | 890.3/s | 26709 | 0 | 4.1 ms | 86.0 ms | 319.5 ms | n/a | 503.8 ms | 1.07 cores | kafka 44% · postgres 97% | 0.23 of 1 | server 1.07 | 2.79 | 259.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe4 | head | 741.9/s | 741.9/s | 22257 | 0 | 3.0 ms | 41.2 ms | 188.4 ms | n/a | 380.9 ms | 1.04 cores | kafka 45% · postgres 90% | 0.20 of 1 | server 1.04 | 3.23 | 261.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe5 | head | 816.1/s | 816.1/s | 24482 | 0 | 5.3 ms | 69.6 ms | 226.3 ms | n/a | 444.4 ms | 1.03 cores | kafka 46% · postgres 94% | 0.23 of 1 | server 1.03 | 2.98 | 257.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **2609.8/s** | 4.6 ms | 160.8 ms | 0.97 | 4912.5/s | 2609.8–2763.3/s |
| E3 | **741.9/s** | 3.0 ms | 188.4 ms | 3.23 | 2374.0/s | 741.9–816.1/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

