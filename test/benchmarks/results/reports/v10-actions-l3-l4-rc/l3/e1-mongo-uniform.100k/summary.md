## e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (31–115 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | ≥ 2222.4/s | 4096 | 1000.1/s | 45% |
| head | 3562.0/s | 256 | 1602.9/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1777.9/s | 1091.8/s | 21837 | 13721 | 12779.5 ms | 16121.9 ms | 17039.4 ms | n/a | 17825.8 ms | 0.91 cores | kafka 39% · mongo 140% | 0.92 of 1 | mongo 1.40 | 2.47 | 138.2 KB | http-429-wow.command.busy 6454 · generator-overload 5488 · exception-ConnectException 1779 |
| probe2-base | base | 1333.4/s | 1232.0/s | 24639 | 2029 | 2457.6 ms | 4325.4 ms | 4948.0 ms | n/a | 5767.2 ms | 0.98 cores | kafka 42% · mongo 142% | 0.81 of 1 | mongo 1.42 | 2.29 | 204.5 KB | http-429-wow.command.busy 2029 |
| probe3-base | base | 1000.1/s | 1000.1/s | 20002 | 0 | 456.7 ms | 1663.0 ms | 2244.6 ms | n/a | 3063.8 ms | 0.90 cores | kafka 34% · mongo 156% | 0.60 of 1 | mongo 1.56 | 2.80 | 245.7 KB | - |
| probe1-head | head | 2849.6/s | 911.3/s | 18226 | 38766 | 14811.1 ms | 15925.2 ms | 16515.1 ms | n/a | 16566.3 ms | 0.58 cores | kafka 43% · mongo 86% | 0.97 of 1 | load generator 0.97 | 2.06 | 36.6 KB | generator-overload 35521 · exception-ConnectException 3245 |
| probe2-head | head | 2137.2/s | 1861.5/s | 37231 | 5512 | 10289.2 ms | 13959.2 ms | 14286.8 ms | n/a | 14854.1 ms | 0.69 cores | kafka 45% · mongo 104% | 0.96 of 1 | mongo 1.04 | 1.17 | 82.8 KB | generator-overload 5512 |
| probe3-head | head | 1602.9/s | 1602.8/s | 32057 | 0 | 1900.5 ms | 3833.9 ms | 3899.4 ms | n/a | 5076.6 ms | 0.74 cores | kafka 45% · mongo 116% | 0.89 of 1 | mongo 1.16 | 1.47 | 112.6 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1000.1/s | 1000.1/s | 40004 | 0 | 206.8 ms | 700.4 ms | 1163.3 ms | n/a | 1777.7 ms | 0.85 cores | kafka 37% · mongo 164% | 0.41 of 1 | mongo 1.64 | 2.86 | 252.3 KB | - |
| r1-head | head | 1602.9/s | 1602.9/s | 64115 | 0 | 5.8 ms | 53.5 ms | 122.9 ms | n/a | 226.3 ms | 0.76 cores | kafka 40% · mongo 131% | 0.54 of 1 | mongo 1.31 | 1.54 | 112.9 KB | - |
| r2-head | head | 1602.9/s | 1602.9/s | 64115 | 0 | 5.2 ms | 50.4 ms | 134.1 ms | n/a | 268.3 ms | 0.76 cores | kafka 41% · mongo 128% | 0.53 of 1 | mongo 1.28 | 1.53 | 114.0 KB | - |
| r2-base | base | 1000.1/s | 1000.1/s | 40004 | 0 | 197.6 ms | 589.8 ms | 831.5 ms | n/a | 1171.5 ms | 0.87 cores | kafka 33% · mongo 167% | 0.41 of 1 | mongo 1.67 | 2.86 | 254.7 KB | - |
| r3-base | base | 1000.1/s | 1000.1/s | 40004 | 0 | 247.8 ms | 610.3 ms | 798.7 ms | n/a | 1048.6 ms | 0.86 cores | kafka 36% · mongo 164% | 0.43 of 1 | mongo 1.64 | 2.85 | 246.8 KB | - |
| r3-head | head | 1602.9/s | 1602.9/s | 64115 | 0 | 5.1 ms | 56.1 ms | 128.0 ms | n/a | 282.6 ms | 0.75 cores | kafka 39% · mongo 126% | 0.52 of 1 | mongo 1.26 | 1.50 | 115.3 KB | - |
| e1-base | base | 1000.1/s | 1000.1/s | 40004 | 0 | 278.5 ms | 716.8 ms | 991.2 ms | n/a | 1450.0 ms | 0.86 cores | kafka 39% · mongo 162% | 0.43 of 1 | mongo 1.62 | 2.87 | 241.9 KB | - |
| e1-head | head | 1000.1/s | 1000.1/s | 40004 | 0 | 2.7 ms | 14.9 ms | 62.5 ms | n/a | 133.1 ms | 0.66 cores | kafka 42% · mongo 115% | 0.35 of 1 | mongo 1.15 | 2.23 | 133.6 KB | - |
| e2-head | head | 1000.1/s | 1000.1/s | 40004 | 0 | 2.7 ms | 23.3 ms | 77.3 ms | n/a | 161.8 ms | 0.64 cores | kafka 42% · mongo 111% | 0.35 of 1 | mongo 1.11 | 2.17 | 131.7 KB | - |
| e2-base | base | 1000.1/s | 1000.1/s | 40004 | 0 | 221.2 ms | 655.4 ms | 831.5 ms | n/a | 1302.5 ms | 0.85 cores | kafka 34% · mongo 167% | 0.41 of 1 | mongo 1.67 | 2.86 | 249.0 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.603 | 0.028 | 0.076 | 0.106 | - | 0.127 | 0.540 | 0.447 |
| 2 | 1.603 | 0.027 | 0.086 | 0.161 | - | 0.229 | 0.535 | 0.448 |
| 3 | 1.603 | 0.020 | 0.092 | 0.160 | - | 0.270 | 0.525 | 0.467 |

Pairs at one rate for both arms, 1000.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.010 | 0.021 | 0.063 | - | 0.092 | 0.777 | 0.553 |
| 2 | 1.000 | 0.012 | 0.036 | 0.093 | - | 0.124 | 0.760 | 0.529 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +25.3% (±25.0%) |
| e1-mongo-uniform@100k | r1-head | steady | **p99 > 10× p50** | p50 5.8 ms, p99 122.9 ms |
| e1-mongo-uniform@100k | r2-head | steady | **p99 > 10× p50** | p50 5.2 ms, p99 134.1 ms |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +82.7% (±25.0%) |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.2% (±25.0%) |
| e1-mongo-uniform@100k | r3-head | steady | **p99 > 10× p50** | p50 5.1 ms, p99 128.0 ms |
| e1-mongo-uniform@100k | e1-head | steady | **p99 > 10× p50** | p50 2.7 ms, p99 62.5 ms |
| e1-mongo-uniform@100k | e2-head | steady | **p99 > 10× p50** | p50 2.7 ms, p99 77.3 ms |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +38.0% (±25.0%) |

