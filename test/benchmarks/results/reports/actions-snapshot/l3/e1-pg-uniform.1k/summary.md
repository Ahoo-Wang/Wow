## e1-pg-uniform@1k

E1 write on PostgreSQL: head against baseline.

E1, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | ≥ 2059.9/s | 4096 | 695.2/s | 34% (**not sustained**) |
| head | ≥ 2165.8/s | 4096 | 730.9/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1647.9/s | 595.9/s | 11917 | 21041 | 3113.0 ms | 5242.9 ms | 6062.1 ms | n/a | 7110.7 ms | 1.13 cores | kafka 68% · postgres 17% | 0.45 of 2 | server 1.13 | 3.34 | 35.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | generator-overload 21041 |
| probe2-base | base | 1235.9/s | 585.5/s | 11710 | 13008 | 3113.0 ms | 5341.2 ms | 6258.7 ms | n/a | 7241.7 ms | 1.14 cores | kafka 66% · postgres 18% | 0.45 of 2 | server 1.14 | 3.38 | 45.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | generator-overload 13008 |
| probe3-base | base | 926.9/s | 608.2/s | 12164 | 6374 | 2965.5 ms | 4816.9 ms | 5668.9 ms | n/a | 6389.8 ms | 1.16 cores | kafka 66% · postgres 18% | 0.41 of 2 | server 1.16 | 3.28 | 63.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | generator-overload 6374 |
| probe4-base | base | 695.2/s | 543.0/s | 10859 | 3045 | 3113.0 ms | 5406.7 ms | 6422.5 ms | n/a | 7209.0 ms | 1.16 cores | kafka 65% · postgres 17% | 0.42 of 2 | server 1.16 | 3.64 | 78.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 8 s | generator-overload 3045 |
| probe1-head | head | 1732.6/s | 529.0/s | 10581 | 24071 | 3620.9 ms | 5996.5 ms | 7045.1 ms | n/a | 7798.8 ms | 1.13 cores | kafka 68% · postgres 16% | 0.50 of 2 | server 1.13 | 3.73 | 30.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 24071 |
| probe2-head | head | 1299.5/s | 566.9/s | 11337 | 14653 | 3227.6 ms | 5439.5 ms | 6553.6 ms | n/a | 7602.2 ms | 1.19 cores | kafka 63% · postgres 17% | 0.46 of 2 | server 1.19 | 3.52 | 43.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | generator-overload 14653 |
| probe3-head | head | 974.6/s | 587.4/s | 11748 | 7744 | 3096.6 ms | 5242.9 ms | 6422.5 ms | n/a | 7929.9 ms | 1.17 cores | kafka 64% · postgres 18% | 0.47 of 2 | server 1.17 | 3.39 | 60.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | generator-overload 7744 |
| probe4-head | head | 730.9/s | 558.0/s | 11160 | 3458 | 3096.6 ms | 5341.2 ms | 6225.9 ms | n/a | 7110.7 ms | 1.17 cores | kafka 65% · postgres 18% | 0.43 of 2 | server 1.17 | 3.59 | 79.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | generator-overload 3458 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 695.2/s | 693.9/s | 27754 | 53 | 340.0 ms | 2113.5 ms | 3063.8 ms | n/a | 4112.4 ms | 1.10 cores | kafka 56% · postgres 37% | 0.28 of 2 | server 1.10 | 2.92 | 100.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 15 s | generator-overload 53 |
| r1-head | head | 730.9/s | 724.1/s | 28966 | 270 | 569.3 ms | 2490.4 ms | 3424.3 ms | n/a | 4390.9 ms | 1.10 cores | kafka 57% · postgres 35% | 0.28 of 2 | server 1.10 | 2.79 | 102.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 270 |
| r2-head | head | 730.9/s | 701.9/s | 28074 | 1162 | 585.7 ms | 2605.1 ms | 5996.5 ms | n/a | 9699.3 ms | 1.13 cores | kafka 55% · postgres 34% | 0.28 of 2 | server 1.13 | 2.88 | 100.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | generator-overload 851 · http-504-wow.wait.timeout 311 |
| r2-base | base | 695.2/s | 689.9/s | 27595 | 212 | 368.6 ms | 2244.6 ms | 3162.1 ms | n/a | 4358.1 ms | 1.11 cores | kafka 55% · postgres 37% | 0.28 of 2 | server 1.11 | 2.93 | 100.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | generator-overload 212 |
| r3-base | base | 695.2/s | 693.4/s | 27735 | 72 | 497.7 ms | 2605.1 ms | 3424.3 ms | n/a | 4685.8 ms | 1.10 cores | kafka 58% · postgres 34% | 0.27 of 2 | server 1.10 | 2.92 | 98.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 72 |
| r3-head | head | 730.9/s | 713.8/s | 28553 | 683 | 651.3 ms | 2687.0 ms | 3457.0 ms | n/a | 4522.0 ms | 1.14 cores | kafka 54% · postgres 33% | 0.28 of 2 | server 1.14 | 2.83 | 101.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | generator-overload 683 |
| e1-base | base | 695.2/s | 688.9/s | 27557 | 250 | 528.4 ms | 2670.6 ms | 3735.6 ms | n/a | 5013.5 ms | 1.08 cores | kafka 60% · postgres 34% | 0.27 of 2 | server 1.08 | 2.93 | 97.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 250 |
| e1-head | head | 695.2/s | 670.1/s | 26804 | 1003 | 553.0 ms | 2310.1 ms | 6225.9 ms | n/a | 10354.7 ms | 1.10 cores | kafka 58% · postgres 33% | 0.30 of 2 | server 1.10 | 3.00 | 99.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | http-504-wow.wait.timeout 732 · generator-overload 271 |
| e2-head | head | 695.2/s | 677.4/s | 27094 | 713 | 639.0 ms | 2719.7 ms | 3588.1 ms | n/a | 4587.5 ms | 1.13 cores | kafka 55% · postgres 33% | 0.26 of 2 | server 1.13 | 2.97 | 101.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 713 |
| e2-base | base | 695.2/s | 684.1/s | 27364 | 443 | 391.2 ms | 1892.4 ms | 2818.0 ms | n/a | 3899.4 ms | 1.11 cores | kafka 55% · postgres 36% | 0.28 of 2 | server 1.11 | 2.96 | 98.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 11 s | generator-overload 443 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.044 | 1.675 | 1.178 | 1.118 | - | 1.068 | 0.954 | 1.021 |
| 2 | 1.017 | 1.589 | 1.161 | 1.896 | - | 2.226 | 0.983 | 0.991 |
| 3 | 1.029 | 1.309 | 1.031 | 1.010 | - | 0.965 | 0.969 | 1.028 |

Pairs at one rate for both arms, 695.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.973 | 1.047 | 0.865 | 1.667 | - | 2.065 | 1.022 | 1.021 |
| 2 | 0.990 | 1.634 | 1.437 | 1.273 | - | 1.176 | 1.005 | 1.027 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-pg-uniform@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -73.4% (±25.0%) |
| e1-pg-uniform@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -65.7% (±25.0%) |
| e1-pg-uniform@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: throughput +8.6% (±5.0%), p50 -72.3% (±25.0%) |
| e1-pg-uniform@1k | r2-head | steady | **error spike** | 1162 of 29236 failed: generator-overload 851 · http-504-wow.wait.timeout 311; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | r2-head | steady | **throughput dip** | 1 s below half the rate (lowest 224/s) |
| e1-pg-uniform@1k | r2-head | steady | **p99 > 10× p50** | p50 585.7 ms, p99 5996.5 ms |
| e1-pg-uniform@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -87.1% (±25.0%) |
| e1-pg-uniform@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -88.4% (±25.0%) |
| e1-pg-uniform@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -80.7% (±25.0%) |
| e1-pg-uniform@1k | r3-head | steady | **error spike** | 683 of 29236 failed: generator-overload 683; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -81.6% (±25.0%) |
| e1-pg-uniform@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: throughput +7.8% (±5.0%), p50 -54.8% (±25.0%) |
| e1-pg-uniform@1k | e1-head | steady | **error spike** | 1003 of 27807 failed: http-504-wow.wait.timeout 732 · generator-overload 271; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | e1-head | steady | **p99 > 10× p50** | p50 553.0 ms, p99 6225.9 ms |
| e1-pg-uniform@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: throughput +5.4% (±5.0%), p50 -81.7% (±25.0%) |
| e1-pg-uniform@1k | e2-head | steady | **error spike** | 713 of 27807 failed: generator-overload 713; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | e2-head | steady | **throughput dip** | 1 s below half the rate (lowest 206/s) |
| e1-pg-uniform@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -76.2% (±25.0%) |
| e1-pg-uniform@1k | e2-base | steady | **error spike** | 443 of 27807 failed: generator-overload 443; generator-overload: 2000 in flight at most (per-target ceiling) |

