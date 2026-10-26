## e3-pg@100k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (31–56 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1250.4/s | 64 | 750.2/s | 60% |
| head | 2410.1/s | 1024 | 1446.1/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1000.3/s | 848.9/s | 16978 | 3028 | 3801.1 ms | 5275.6 ms | 6127.6 ms | n/a | 7241.7 ms | 1.26 cores | kafka 40% · postgres 101% | 0.54 of 1 | server 1.26 | 3.15 | 247.4 KB | http-429-wow.command.busy 3028 |
| probe2-base | base | 750.2/s | 750.2/s | 15004 | 0 | 1204.2 ms | 2981.9 ms | 3227.6 ms | n/a | 3538.9 ms | 1.11 cores | kafka 44% · postgres 112% | 0.57 of 1 | postgres 1.12 | 3.57 | 313.3 KB | - |
| probe1-head | head | 1928.1/s | 1497.4/s | 29948 | 8614 | 12189.7 ms | 15728.6 ms | 16384.0 ms | n/a | 16908.3 ms | 0.98 cores | kafka 47% · postgres 89% | 0.96 of 1 | server 0.98 | 1.57 | 140.1 KB | generator-overload 5795 · http-429-wow.command.busy 1525 · exception-ConnectException 1294 |
| probe2-head | head | 1446.1/s | 1446.1/s | 28922 | 0 | 5505.0 ms | 6324.2 ms | 8454.1 ms | n/a | 8847.4 ms | 1.01 cores | kafka 50% · postgres 99% | 0.96 of 1 | server 1.01 | 1.73 | 221.1 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 750.2/s | 750.2/s | 30008 | 0 | 2039.8 ms | 2965.5 ms | 3342.3 ms | n/a | 3751.9 ms | 1.15 cores | kafka 46% · postgres 110% | 0.40 of 1 | server 1.15 | 3.61 | 315.9 KB | - |
| r1-head | head | 1446.1/s | 1446.1/s | 57844 | 0 | 67.6 ms | 368.6 ms | 540.7 ms | n/a | 692.2 ms | 0.96 cores | kafka 49% · postgres 110% | 0.58 of 1 | postgres 1.10 | 1.76 | 193.7 KB | - |
| r2-head | head | 1446.1/s | 1446.1/s | 57844 | 0 | 53.2 ms | 380.9 ms | 614.4 ms | n/a | 831.5 ms | 0.99 cores | kafka 46% · postgres 111% | 0.57 of 1 | postgres 1.11 | 1.77 | 193.4 KB | - |
| r2-base | base | 750.2/s | 750.2/s | 30008 | 0 | 8.2 ms | 216.1 ms | 401.4 ms | n/a | 577.5 ms | 1.03 cores | kafka 41% · postgres 105% | 0.30 of 1 | postgres 1.05 | 3.31 | 297.1 KB | - |
| r3-base | base | 750.2/s | 750.2/s | 30008 | 0 | 6.3 ms | 102.9 ms | 331.8 ms | n/a | 602.1 ms | 1.04 cores | kafka 39% · postgres 105% | 0.29 of 1 | postgres 1.05 | 3.30 | 298.1 KB | - |
| r3-head | head | 1446.1/s | 1446.1/s | 57844 | 0 | 62.0 ms | 421.9 ms | 647.2 ms | n/a | 823.3 ms | 0.94 cores | kafka 48% · postgres 110% | 0.56 of 1 | postgres 1.10 | 1.74 | 191.9 KB | - |
| e1-base | base | 750.2/s | 750.2/s | 30008 | 0 | 34.0 ms | 331.8 ms | 532.5 ms | n/a | 766.0 ms | 1.02 cores | kafka 48% · postgres 110% | 0.33 of 1 | postgres 1.10 | 3.47 | 287.6 KB | - |
| e1-head | head | 750.2/s | 750.2/s | 30008 | 0 | 6.2 ms | 63.5 ms | 242.7 ms | n/a | 397.3 ms | 0.93 cores | kafka 51% · postgres 96% | 0.31 of 1 | postgres 0.96 | 3.19 | 214.7 KB | - |
| e2-head | head | 750.2/s | 750.2/s | 30008 | 0 | 4.4 ms | 57.3 ms | 180.2 ms | n/a | 397.3 ms | 0.96 cores | kafka 45% · postgres 95% | 0.30 of 1 | server 0.96 | 3.14 | 215.5 KB | - |
| e2-base | base | 750.2/s | 750.2/s | 30008 | 0 | 32.0 ms | 460.8 ms | 593.9 ms | n/a | 782.3 ms | 1.01 cores | kafka 44% · postgres 110% | 0.34 of 1 | postgres 1.10 | 3.41 | 289.4 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.928 | 0.033 | 0.124 | 0.162 | - | 0.184 | 0.489 | 0.613 |
| 2 | 1.928 | 6.501 | 1.763 | 1.531 | - | 1.440 | 0.533 | 0.651 |
| 3 | 1.928 | 9.779 | 4.100 | 1.951 | - | 1.367 | 0.529 | 0.644 |

Pairs at one rate for both arms, 750.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.181 | 0.191 | 0.456 | - | 0.519 | 0.920 | 0.747 |
| 2 | 1.000 | 0.136 | 0.124 | 0.303 | - | 0.508 | 0.921 | 0.745 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -25.3% (±25.0%) |
| e3-pg@100k | r2-head | steady | **p99 > 10× p50** | p50 53.2 ms, p99 614.4 ms |
| e3-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +47.0% (±25.0%) |
| e3-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 8.2 ms, p99 401.4 ms |
| e3-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -37.6% (±25.0%) |
| e3-pg@100k | r3-base | steady | **p99 > 10× p50** | p50 6.3 ms, p99 331.8 ms |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.2% (±25.0%) |
| e3-pg@100k | r3-head | steady | **p99 > 10× p50** | p50 62.0 ms, p99 647.2 ms |
| e3-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +88.3% (±25.0%) |
| e3-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 34.0 ms, p99 532.5 ms |
| e3-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -28.2% (±25.0%) |
| e3-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 6.2 ms, p99 242.7 ms |
| e3-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -50.7% (±25.0%) |
| e3-pg@100k | e2-head | steady | **p99 > 10× p50** | p50 4.4 ms, p99 180.2 ms |
| e3-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -28.1% (±25.0%) |
| e3-pg@100k | e2-base | steady | **p99 > 10× p50** | p50 32.0 ms, p99 593.9 ms |

