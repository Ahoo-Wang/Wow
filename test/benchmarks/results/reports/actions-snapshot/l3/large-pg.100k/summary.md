## large-pg@100k

E1 with a large payload on PostgreSQL, as large-mongo.

E1-large, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (47–58 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 813.8/s | 8 | 651.0/s | 80% |
| head | 955.5/s | 64 | 764.4/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 651.0/s | 651.0/s | 13020 | 0 | 11.0 ms | 246.8 ms | 464.9 ms | n/a | 651.3 ms | 0.59 cores | kafka 46% · postgres 58% | 0.27 of 2 | server 0.59 | 2.50 | 240.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| probe1-head | head | 764.4/s | 764.4/s | 15288 | 0 | 59.1 ms | 331.8 ms | 462.8 ms | n/a | 565.2 ms | 0.63 cores | kafka 46% · postgres 64% | 0.32 of 2 | postgres 0.64 | 2.26 | 239.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 651.0/s | 651.0/s | 26040 | 0 | 13.4 ms | 544.8 ms | 958.5 ms | n/a | 1302.5 ms | 0.54 cores | kafka 47% · postgres 53% | 0.19 of 2 | server 0.54 | 2.37 | 239.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | - |
| r1-head | head | 764.4/s | 764.4/s | 30576 | 0 | 132.1 ms | 663.6 ms | 958.5 ms | n/a | 1294.3 ms | 0.56 cores | kafka 48% · postgres 56% | 0.21 of 2 | postgres 0.56 | 2.09 | 239.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 16 s | - |
| r2-head | head | 764.4/s | 764.4/s | 30576 | 0 | 113.7 ms | 561.2 ms | 798.7 ms | n/a | 1081.3 ms | 0.58 cores | kafka 45% · postgres 58% | 0.23 of 2 | postgres 0.58 | 2.10 | 238.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 14 s | - |
| r2-base | base | 651.0/s | 651.0/s | 26040 | 0 | 223.2 ms | 933.9 ms | 1278.0 ms | n/a | 1630.2 ms | 0.52 cores | kafka 46% · postgres 48% | 0.19 of 2 | server 0.52 | 2.25 | 233.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 23 s | - |
| r3-base | base | 651.0/s | 651.0/s | 26040 | 0 | 64.5 ms | 561.2 ms | 745.5 ms | n/a | 892.9 ms | 0.49 cores | kafka 45% · postgres 51% | 0.18 of 2 | postgres 0.51 | 2.22 | 235.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | - |
| r3-head | head | 764.4/s | 764.4/s | 30576 | 0 | 160.8 ms | 753.7 ms | 995.3 ms | n/a | 1163.3 ms | 0.56 cores | kafka 46% · postgres 59% | 0.21 of 2 | postgres 0.59 | 2.12 | 240.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 16 s | - |
| e1-base | base | 651.0/s | 651.0/s | 26040 | 0 | 12.1 ms | 309.2 ms | 444.4 ms | n/a | 561.2 ms | 0.58 cores | kafka 49% · postgres 53% | 0.19 of 2 | server 0.58 | 2.46 | 240.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| e1-head | head | 651.0/s | 651.0/s | 26040 | 0 | 88.1 ms | 581.6 ms | 819.2 ms | n/a | 1171.5 ms | 0.50 cores | kafka 46% · postgres 54% | 0.20 of 2 | postgres 0.54 | 2.30 | 238.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 18 s | - |
| e2-head | head | 651.0/s | 651.0/s | 26040 | 0 | 78.3 ms | 655.4 ms | 946.2 ms | n/a | 1220.6 ms | 0.52 cores | kafka 46% · postgres 53% | 0.19 of 2 | postgres 0.53 | 2.33 | 238.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 15 s | - |
| e2-base | base | 651.0/s | 651.0/s | 26040 | 0 | 94.7 ms | 622.6 ms | 856.1 ms | n/a | 1081.3 ms | 0.49 cores | kafka 48% · postgres 51% | 0.19 of 2 | postgres 0.51 | 2.27 | 236.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 17 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.174 | 9.876 | 1.218 | 1.000 | - | 0.994 | 0.881 | 0.999 |
| 2 | 1.174 | 0.509 | 0.601 | 0.625 | - | 0.663 | 0.932 | 1.019 |
| 3 | 1.174 | 2.492 | 1.343 | 1.335 | - | 1.303 | 0.951 | 1.021 |

Pairs at one rate for both arms, 651.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 7.281 | 1.881 | 1.843 | - | 2.088 | 0.932 | 0.991 |
| 2 | 1.000 | 0.827 | 1.053 | 1.105 | - | 1.129 | 1.026 | 1.009 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -90.3% (±25.0%) |
| large-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 13.4 ms, p99 958.5 ms |
| large-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +42.5% (±25.0%) |
| large-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +79.4% (±25.0%) |
| large-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -31.1% (±25.0%) |
| large-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -49.2% (±25.0%) |
| large-pg@100k | r3-base | steady | **p99 > 10× p50** | p50 64.5 ms, p99 745.5 ms |
| large-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -58.1% (±25.0%) |
| large-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +43.2% (±25.0%) |
| large-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 12.1 ms, p99 444.4 ms |
| large-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -85.2% (±25.0%) |
| large-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +174.0% (±25.0%) |
| large-pg@100k | e2-head | steady | **p99 > 10× p50** | p50 78.3 ms, p99 946.2 ms |
| large-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +144.2% (±25.0%) |

