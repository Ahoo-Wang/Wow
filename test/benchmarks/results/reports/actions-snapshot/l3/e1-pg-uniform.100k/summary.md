## e1-pg-uniform@100k

E1 write on PostgreSQL: head against baseline.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (65–75 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1990.0/s | 512 | 1592.0/s | 80% |
| head | 1633.6/s | 64 | 1306.9/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1592.0/s | 1592.0/s | 31840 | 0 | 335.9 ms | 958.5 ms | 1179.6 ms | n/a | 1327.1 ms | 0.90 cores | kafka 45% · postgres 64% | 0.59 of 2 | server 0.90 | 1.25 | 86.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | - |
| probe1-head | head | 1306.9/s | 1295.2/s | 25903 | 235 | 234.5 ms | 1015.8 ms | 1450.0 ms | n/a | 1876.0 ms | 0.90 cores | kafka 50% · postgres 63% | 0.51 of 2 | server 0.90 | 1.57 | 95.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 11 s | generator-overload 235 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1592.0/s | 1592.0/s | 63680 | 0 | 170.0 ms | 614.4 ms | 774.1 ms | n/a | 962.6 ms | 0.86 cores | kafka 51% · postgres 65% | 0.55 of 2 | server 0.86 | 1.27 | 87.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 5 s | - |
| r1-head | head | 1306.9/s | 1306.9/s | 52275 | 0 | 37.1 ms | 260.1 ms | 358.4 ms | n/a | 450.6 ms | 0.88 cores | kafka 48% · postgres 66% | 0.49 of 2 | server 0.88 | 1.54 | 94.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| r2-head | head | 1306.9/s | 1306.9/s | 52275 | 0 | 59.1 ms | 244.7 ms | 389.1 ms | n/a | 573.4 ms | 0.86 cores | kafka 50% · postgres 66% | 0.50 of 2 | server 0.86 | 1.55 | 94.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| r2-base | base | 1592.0/s | 1592.0/s | 63680 | 0 | 261.1 ms | 897.0 ms | 1171.5 ms | n/a | 1335.3 ms | 0.87 cores | kafka 50% · postgres 66% | 0.55 of 2 | server 0.87 | 1.27 | 87.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 17 s | - |
| r3-base | base | 1592.0/s | 1592.0/s | 63680 | 0 | 206.8 ms | 602.1 ms | 766.0 ms | n/a | 1036.3 ms | 0.86 cores | kafka 50% · postgres 67% | 0.54 of 2 | server 0.86 | 1.27 | 87.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 15 s | - |
| r3-head | head | 1306.9/s | 1306.9/s | 52275 | 0 | 25.3 ms | 210.9 ms | 305.2 ms | n/a | 411.6 ms | 0.86 cores | kafka 49% · postgres 67% | 0.49 of 2 | server 0.86 | 1.55 | 94.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| e1-base | base | 1306.9/s | 1306.9/s | 52275 | 0 | 17.5 ms | 301.1 ms | 688.1 ms | n/a | 974.8 ms | 0.84 cores | kafka 49% · postgres 69% | 0.51 of 2 | server 0.84 | 1.54 | 91.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| e1-head | head | 1306.9/s | 1306.9/s | 52275 | 0 | 34.8 ms | 223.2 ms | 309.2 ms | n/a | 421.9 ms | 0.88 cores | kafka 49% · postgres 64% | 0.48 of 2 | server 0.88 | 1.54 | 95.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| e2-head | head | 1306.9/s | 1306.9/s | 52275 | 0 | 46.3 ms | 241.7 ms | 385.0 ms | n/a | 614.4 ms | 0.90 cores | kafka 50% · postgres 62% | 0.48 of 2 | server 0.90 | 1.55 | 94.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| e2-base | base | 1306.9/s | 1306.9/s | 52275 | 0 | 19.5 ms | 207.9 ms | 278.5 ms | n/a | 337.9 ms | 0.88 cores | kafka 49% · postgres 65% | 0.49 of 2 | server 0.88 | 1.54 | 91.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.821 | 0.218 | 0.423 | 0.463 | - | 0.468 | 1.217 | 1.077 |
| 2 | 0.821 | 0.226 | 0.273 | 0.332 | - | 0.429 | 1.215 | 1.075 |
| 3 | 0.821 | 0.123 | 0.350 | 0.398 | - | 0.397 | 1.213 | 1.078 |

Pairs at one rate for both arms, 1306.9/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.985 | 0.741 | 0.449 | - | 0.433 | 1.000 | 1.037 |
| 2 | 1.000 | 2.382 | 1.163 | 1.382 | - | 1.818 | 1.001 | 1.033 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-pg-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -26.4% (±25.0%) |
| e1-pg-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -29.2% (±25.0%) |
| e1-pg-uniform@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +47.6% (±25.0%) |
| e1-pg-uniform@100k | r3-head | steady | **p99 > 10× p50** | p50 25.3 ms, p99 305.2 ms |
| e1-pg-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -53.5% (±25.0%) |
| e1-pg-uniform@100k | e1-base | steady | **p99 > 10× p50** | p50 17.5 ms, p99 688.1 ms |
| e1-pg-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +104.4% (±25.0%) |
| e1-pg-uniform@100k | e2-base | steady | **p99 > 10× p50** | p50 19.5 ms, p99 278.5 ms |

