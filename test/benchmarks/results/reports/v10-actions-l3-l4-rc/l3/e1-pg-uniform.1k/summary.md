## e1-pg-uniform@1k

E1 write on PostgreSQL: head against baseline.

E1, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2087.4/s | 128 | 704.5/s | 34% (**not sustained**) |
| head | 2358.3/s | 256 | 1061.2/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1669.9/s | 879.8/s | 17596 | 15801 | 16318.5 ms | 21626.9 ms | 21626.9 ms | n/a | 21889.0 ms | 1.50 cores | kafka 78% · postgres 43% | 0.93 of 1 | server 1.50 | 3.09 | 44.9 KB | generator-overload 12545 · exception-ConnectException 3256 |
| probe2-base | base | 1252.4/s | 778.6/s | 15573 | 9474 | 18743.3 ms | 23724.0 ms | 25296.9 ms | n/a | 26607.6 ms | 1.61 cores | kafka 81% · postgres 42% | 0.89 of 1 | server 1.61 | 3.64 | 64.4 KB | generator-overload 4616 · exception-ConnectException 3112 · http-429-wow.command.busy 1732 · http-504-wow.wait.timeout 14 |
| probe3-base | base | 939.3/s | 500.9/s | 10018 | 8768 | 10289.2 ms | 14745.6 ms | 15859.7 ms | n/a | 16711.7 ms | 1.63 cores | kafka 82% · postgres 38% | 0.92 of 1 | server 1.63 | 5.64 | 83.2 KB | http-429-wow.command.busy 7301 · http-504-wow.wait.timeout 1467 |
| probe4-base | base | 704.5/s | 692.9/s | 13857 | 233 | 3113.0 ms | 8912.9 ms | 10616.8 ms | n/a | 11468.8 ms | 1.66 cores | kafka 74% · postgres 45% | 0.86 of 1 | server 1.66 | 4.12 | 123.3 KB | http-504-wow.wait.timeout 233 |
| probe1-head | head | 1886.6/s | 689.4/s | 13787 | 23945 | 19136.5 ms | 21233.7 ms | 21364.7 ms | n/a | 21408.3 ms | 1.49 cores | kafka 81% · postgres 39% | 0.94 of 1 | server 1.49 | 3.89 | 29.7 KB | generator-overload 16971 · exception-ConnectException 6974 |
| probe2-head | head | 1415.0/s | 883.8/s | 17676 | 10624 | 17694.7 ms | 19267.6 ms | 22282.2 ms | n/a | 22675.5 ms | 1.56 cores | kafka 85% · postgres 39% | 0.94 of 1 | server 1.56 | 3.16 | 55.2 KB | generator-overload 5388 · exception-ConnectException 5236 |
| probe3-head | head | 1061.2/s | 1061.2/s | 21224 | 0 | 8912.9 ms | 9830.4 ms | 10747.9 ms | n/a | 12255.2 ms | 1.75 cores | kafka 84% · postgres 35% | 0.94 of 1 | server 1.75 | 2.77 | 91.3 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 704.5/s | 704.5/s | 28180 | 0 | 10.3 ms | 1630.2 ms | 4423.7 ms | n/a | 7503.9 ms | 1.61 cores | kafka 53% · postgres 60% | 0.48 of 1 | server 1.61 | 3.90 | 127.5 KB | - |
| r1-head | head | 1061.2/s | 1061.2/s | 42448 | 0 | 321.5 ms | 6193.2 ms | 7241.7 ms | n/a | 7419.3 ms | 1.58 cores | kafka 57% · postgres 61% | 0.80 of 1 | server 1.58 | 2.60 | 95.1 KB | - |
| r2-head | head | 1061.2/s | 1061.2/s | 42448 | 0 | 1728.5 ms | 8388.6 ms | 9044.0 ms | n/a | 9214.9 ms | 1.66 cores | kafka 60% · postgres 58% | 0.82 of 1 | server 1.66 | 2.68 | 99.8 KB | - |
| r2-base | base | 704.5/s | 704.5/s | 28180 | 0 | 4.1 ms | 125.4 ms | 231.4 ms | n/a | 415.7 ms | 1.51 cores | kafka 59% · postgres 55% | 0.43 of 1 | server 1.51 | 3.76 | 109.5 KB | - |
| r3-base | base | 704.5/s | 665.8/s | 26630 | 1550 | 1130.5 ms | 8585.2 ms | 9895.9 ms | n/a | 11468.8 ms | 1.66 cores | kafka 47% · postgres 74% | 0.58 of 1 | server 1.66 | 4.31 | 166.3 KB | http-504-wow.wait.timeout 788 · http-429-wow.command.busy 762 |
| r3-head | head | 1061.2/s | 1061.2/s | 42448 | 0 | 139.3 ms | 6062.1 ms | 6750.2 ms | n/a | 6913.4 ms | 1.61 cores | kafka 59% · postgres 61% | 0.76 of 1 | server 1.61 | 2.64 | 97.7 KB | - |
| e1-base | base | 704.5/s | 704.5/s | 28180 | 0 | 4.0 ms | 74.2 ms | 133.1 ms | n/a | 261.1 ms | 1.50 cores | kafka 57% · postgres 55% | 0.42 of 1 | server 1.50 | 3.73 | 110.7 KB | - |
| e1-head | head | 704.5/s | 704.5/s | 28180 | 0 | 3.4 ms | 47.4 ms | 245.8 ms | n/a | 626.7 ms | 1.46 cores | kafka 58% · postgres 54% | 0.39 of 1 | server 1.46 | 3.67 | 93.3 KB | - |
| e2-head | head | 704.5/s | 704.5/s | 28180 | 0 | 5.0 ms | 66.6 ms | 181.2 ms | n/a | 407.6 ms | 1.60 cores | kafka 54% · postgres 56% | 0.40 of 1 | server 1.60 | 3.83 | 94.0 KB | - |
| e2-base | base | 704.5/s | 704.5/s | 28180 | 0 | 6.4 ms | 360.4 ms | 909.3 ms | n/a | 3473.4 ms | 1.61 cores | kafka 59% · postgres 57% | 0.44 of 1 | server 1.61 | 3.94 | 117.4 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.506 | 31.208 | 3.799 | 1.637 | - | 0.989 | 0.667 | 0.745 |
| 2 | 1.506 | 418.830 | 66.874 | 39.080 | - | 22.165 | 0.713 | 0.911 |
| 3 | 1.594 | 0.123 | 0.706 | 0.682 | - | 0.603 | 0.613 | 0.588 |

Pairs at one rate for both arms, 704.5/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.841 | 0.638 | 1.846 | - | 2.400 | 0.985 | 0.843 |
| 2 | 1.000 | 0.789 | 0.185 | 0.199 | - | 0.117 | 0.974 | 0.801 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-pg-uniform@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -93.9% (±25.0%) |
| e1-pg-uniform@1k | r1-base | steady | **p99 > 10× p50** | p50 10.3 ms, p99 4423.7 ms |
| e1-pg-uniform@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -99.0% (±25.0%) |
| e1-pg-uniform@1k | r1-head | steady | **p99 > 10× p50** | p50 321.5 ms, p99 7241.7 ms |
| e1-pg-uniform@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -98.2% (±25.0%) |
| e1-pg-uniform@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.3% (±25.0%) |
| e1-pg-uniform@1k | r2-base | steady | **p99 > 10× p50** | p50 4.1 ms, p99 231.4 ms |
| e1-pg-uniform@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: throughput +12.4% (±5.0%), p50 -82.2% (±25.0%) |
| e1-pg-uniform@1k | r3-base | steady | **error spike** | 1550 of 28180 failed: http-504-wow.wait.timeout 788 · http-429-wow.command.busy 762 |
| e1-pg-uniform@1k | r3-base | steady | **throughput dip** | 1 s below half the rate (lowest 295/s) |
| e1-pg-uniform@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -99.1% (±25.0%) |
| e1-pg-uniform@1k | r3-head | steady | **p99 > 10× p50** | p50 139.3 ms, p99 6750.2 ms |
| e1-pg-uniform@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -38.6% (±25.0%) |
| e1-pg-uniform@1k | e1-base | steady | **p99 > 10× p50** | p50 4.0 ms, p99 133.1 ms |
| e1-pg-uniform@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.4% (±25.0%) |
| e1-pg-uniform@1k | e1-head | steady | **p99 > 10× p50** | p50 3.4 ms, p99 245.8 ms |
| e1-pg-uniform@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -56.5% (±25.0%) |
| e1-pg-uniform@1k | e2-head | steady | **p99 > 10× p50** | p50 5.0 ms, p99 181.2 ms |
| e1-pg-uniform@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.1% (±25.0%) |
| e1-pg-uniform@1k | e2-base | steady | **p99 > 10× p50** | p50 6.4 ms, p99 909.3 ms |
| e1-pg-uniform@1k | probe1-base | overload (probe) | **tail read the store** | gap=5 |
| e1-pg-uniform@1k | probe2-base | overload (probe) | **tail read the store** | gap=10 |
| e1-pg-uniform@1k | probe3-base | overload (probe) | **tail read the store** | gap=10 |
| e1-pg-uniform@1k | probe4-base | overload (probe) | **tail read the store** | gap=2 |

