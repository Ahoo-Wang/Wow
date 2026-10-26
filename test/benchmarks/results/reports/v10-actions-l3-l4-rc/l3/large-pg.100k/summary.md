## large-pg@100k

E1 with a large payload on PostgreSQL, as large-mongo.

E1-large, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (52–95 s), then waited until the nodes were quiet again (4–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1126.1/s | 64 | 675.7/s | 60% |
| head | 1355.8/s | 128 | 1084.6/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 900.9/s | 803.0/s | 16059 | 1959 | 12255.2 ms | 15990.8 ms | 17170.4 ms | n/a | 18743.3 ms | 1.08 cores | kafka 59% · postgres 100% | 0.91 of 1 | server 1.08 | 3.32 | 212.7 KB | http-429-wow.command.busy 1959 |
| probe2-base | base | 675.7/s | 675.7/s | 13514 | 0 | 1105.9 ms | 3719.2 ms | 3915.8 ms | n/a | 4358.1 ms | 0.97 cores | kafka 58% · postgres 104% | 0.89 of 1 | postgres 1.04 | 3.83 | 298.7 KB | - |
| probe1-head | head | 1084.6/s | 1084.5/s | 21691 | 0 | 6094.8 ms | 8585.2 ms | 8847.4 ms | n/a | 9367.9 ms | 1.01 cores | kafka 54% · postgres 102% | 0.95 of 1 | postgres 1.02 | 2.37 | 249.9 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 675.7/s | 675.7/s | 27027 | 0 | 9.5 ms | 209.9 ms | 505.9 ms | n/a | 757.8 ms | 0.98 cores | kafka 54% · postgres 98% | 0.38 of 1 | postgres 0.98 | 3.70 | 271.7 KB | - |
| r1-head | head | 1084.6/s | 1084.6/s | 43384 | 0 | 178.2 ms | 2375.7 ms | 2949.1 ms | n/a | 3440.6 ms | 0.96 cores | kafka 57% · postgres 111% | 0.68 of 1 | postgres 1.11 | 2.44 | 238.4 KB | - |
| r2-head | head | 1084.6/s | 1084.6/s | 43384 | 0 | 185.3 ms | 1499.1 ms | 1851.4 ms | n/a | 2228.2 ms | 0.91 cores | kafka 51% · postgres 107% | 0.62 of 1 | postgres 1.07 | 2.30 | 230.4 KB | - |
| r2-base | base | 675.7/s | 675.7/s | 27027 | 0 | 11.2 ms | 294.9 ms | 651.3 ms | n/a | 999.4 ms | 0.97 cores | kafka 54% · postgres 98% | 0.40 of 1 | postgres 0.98 | 3.69 | 270.9 KB | - |
| r3-base | base | 675.7/s | 675.7/s | 27027 | 0 | 9.0 ms | 226.3 ms | 667.6 ms | n/a | 938.0 ms | 0.96 cores | kafka 55% · postgres 98% | 0.40 of 1 | postgres 0.98 | 3.69 | 271.3 KB | - |
| r3-head | head | 1084.6/s | 1056.5/s | 42260 | 1124 | 2457.6 ms | 6357.0 ms | 6848.5 ms | n/a | 7405.6 ms | 1.12 cores | kafka 55% · postgres 103% | 0.77 of 1 | server 1.12 | 2.55 | 252.8 KB | http-429-wow.command.busy 1124 |
| e1-base | base | 675.7/s | 675.7/s | 27027 | 0 | 9.1 ms | 247.8 ms | 626.7 ms | n/a | 1073.2 ms | 0.96 cores | kafka 54% · postgres 95% | 0.39 of 1 | server 0.96 | 3.64 | 272.2 KB | - |
| e1-head | head | 675.7/s | 675.7/s | 27027 | 0 | 3.1 ms | 35.8 ms | 280.6 ms | n/a | 602.1 ms | 0.82 cores | kafka 59% · postgres 78% | 0.37 of 1 | server 0.82 | 3.24 | 235.7 KB | - |
| e2-head | head | 675.7/s | 675.7/s | 27027 | 0 | 3.5 ms | 125.4 ms | 409.6 ms | n/a | 618.5 ms | 0.88 cores | kafka 57% · postgres 80% | 0.37 of 1 | server 0.88 | 3.33 | 234.4 KB | - |
| e2-base | base | 675.7/s | 675.7/s | 27027 | 0 | 10.7 ms | 329.7 ms | 692.2 ms | n/a | 1089.5 ms | 0.96 cores | kafka 54% · postgres 96% | 0.36 of 1 | server 0.96 | 3.63 | 270.4 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.605 | 18.813 | 11.317 | 5.830 | - | 4.541 | 0.660 | 0.877 |
| 2 | 1.605 | 16.550 | 5.083 | 2.843 | - | 2.230 | 0.623 | 0.851 |
| 3 | 1.564 | 272.370 | 28.091 | 10.258 | - | 7.895 | 0.691 | 0.932 |

Pairs at one rate for both arms, 675.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.343 | 0.145 | 0.448 | - | 0.561 | 0.891 | 0.866 |
| 2 | 1.000 | 0.325 | 0.380 | 0.592 | - | 0.568 | 0.918 | 0.867 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -38.3% (±25.0%) |
| large-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 9.5 ms, p99 505.9 ms |
| large-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -89.2% (±25.0%) |
| large-pg@100k | r1-head | steady | **p99 > 10× p50** | p50 178.2 ms, p99 2949.1 ms |
| large-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -88.3% (±25.0%) |
| large-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +259.5% (±25.0%) |
| large-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 11.2 ms, p99 651.3 ms |
| large-pg@100k | r3-base | steady | **p99 > 10× p50** | p50 9.0 ms, p99 667.6 ms |
| large-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: throughput +5.5% (±5.0%), p50 -82.7% (±25.0%) |
| large-pg@100k | r3-head | steady | **error spike** | 1124 of 43384 failed: http-429-wow.command.busy 1124 |
| large-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +30.9% (±25.0%) |
| large-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 9.1 ms, p99 626.7 ms |
| large-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.3% (±25.0%) |
| large-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 3.1 ms, p99 280.6 ms |
| large-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -43.8% (±25.0%) |
| large-pg@100k | e2-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 409.6 ms |
| large-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +42.3% (±25.0%) |
| large-pg@100k | e2-base | steady | **p99 > 10× p50** | p50 10.7 ms, p99 692.2 ms |

