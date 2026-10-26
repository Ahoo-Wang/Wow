## e1-pg-uniform@100k

E1 write on PostgreSQL: head against baseline.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (30–47 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2487.0/s | 32 | 1989.6/s | 80% |
| head | 4361.1/s | 128 | 1962.5/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1989.6/s | 1989.6/s | 39792 | 0 | 4325.4 ms | 5668.9 ms | 7471.1 ms | n/a | 7536.6 ms | 0.87 cores | kafka 41% · postgres 90% | 0.93 of 1 | load generator 0.93 | 1.09 | 121.0 KB | - |
| probe1-head | head | 3488.9/s | 1588.0/s | 31761 | 38016 | 9633.8 ms | 14024.7 ms | 14155.8 ms | n/a | 14286.8 ms | 0.66 cores | kafka 44% · postgres 47% | 0.97 of 1 | load generator 0.97 | 0.99 | 38.2 KB | generator-overload 38016 |
| probe2-head | head | 2616.7/s | 2026.2/s | 40524 | 11810 | 8454.1 ms | 10551.3 ms | 13369.3 ms | n/a | 13854.3 ms | 0.70 cores | kafka 41% · postgres 55% | 0.97 of 1 | load generator 0.97 | 0.82 | 66.2 KB | generator-overload 11810 |
| probe3-head | head | 1962.5/s | 1962.5/s | 39250 | 0 | 5.8 ms | 2261.0 ms | 2342.9 ms | n/a | 2355.9 ms | 0.90 cores | kafka 41% · postgres 69% | 0.78 of 1 | server 0.90 | 1.02 | 90.9 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1989.6/s | 1989.6/s | 79584 | 0 | 20.7 ms | 1728.5 ms | 2089.0 ms | n/a | 2211.8 ms | 0.95 cores | kafka 38% · postgres 96% | 0.76 of 1 | postgres 0.96 | 1.15 | 120.6 KB | - |
| r1-head | head | 1962.5/s | 1962.5/s | 78500 | 0 | 2.4 ms | 31.2 ms | 110.6 ms | n/a | 327.7 ms | 0.95 cores | kafka 46% · postgres 79% | 0.65 of 1 | server 0.95 | 1.12 | 86.7 KB | - |
| r2-head | head | 1962.5/s | 1962.5/s | 78500 | 0 | 3.5 ms | 63.5 ms | 166.9 ms | n/a | 342.0 ms | 0.93 cores | kafka 45% · postgres 79% | 0.66 of 1 | server 0.93 | 1.11 | 86.3 KB | - |
| r2-base | base | 1989.6/s | 1989.6/s | 79584 | 0 | 1499.1 ms | 3751.9 ms | 4259.8 ms | n/a | 4456.4 ms | 0.93 cores | kafka 43% · postgres 97% | 0.90 of 1 | postgres 0.97 | 1.17 | 119.6 KB | - |
| r3-base | base | 1989.6/s | 1989.6/s | 79584 | 0 | 172.0 ms | 2965.5 ms | 3358.7 ms | n/a | 3501.2 ms | 0.96 cores | kafka 41% · postgres 97% | 0.83 of 1 | postgres 0.97 | 1.18 | 119.1 KB | - |
| r3-head | head | 1962.5/s | 1962.5/s | 78500 | 0 | 2.6 ms | 43.8 ms | 140.3 ms | n/a | 261.1 ms | 0.96 cores | kafka 45% · postgres 79% | 0.65 of 1 | server 0.96 | 1.12 | 86.8 KB | - |
| e1-base | base | 1962.5/s | 1962.5/s | 78500 | 0 | 72.7 ms | 2326.5 ms | 2424.8 ms | n/a | 2523.1 ms | 0.97 cores | kafka 39% · postgres 99% | 0.78 of 1 | postgres 0.99 | 1.20 | 120.6 KB | - |
| e1-head | head | 1962.5/s | 1962.5/s | 78500 | 0 | 2.4 ms | 43.0 ms | 149.5 ms | n/a | 297.0 ms | 0.95 cores | kafka 45% · postgres 78% | 0.64 of 1 | server 0.95 | 1.11 | 86.5 KB | - |
| e2-head | head | 1962.5/s | 1962.5/s | 78500 | 0 | 1.5 ms | 18.6 ms | 106.5 ms | n/a | 311.3 ms | 0.97 cores | kafka 39% · postgres 76% | 0.60 of 1 | server 0.97 | 1.08 | 86.2 KB | - |
| e2-base | base | 1962.5/s | 1962.5/s | 78500 | 0 | 426.0 ms | 1941.5 ms | 2211.8 ms | n/a | 2457.6 ms | 0.96 cores | kafka 46% · postgres 103% | 0.85 of 1 | postgres 1.03 | 1.25 | 117.2 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.986 | 0.118 | 0.018 | 0.053 | - | 0.148 | 0.976 | 0.719 |
| 2 | 0.986 | 0.002 | 0.017 | 0.039 | - | 0.077 | 0.944 | 0.721 |
| 3 | 0.986 | 0.015 | 0.015 | 0.042 | - | 0.075 | 0.953 | 0.729 |

Pairs at one rate for both arms, 1962.5/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.033 | 0.018 | 0.062 | - | 0.118 | 0.930 | 0.717 |
| 2 | 1.000 | 0.003 | 0.010 | 0.048 | - | 0.127 | 0.864 | 0.736 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-pg-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -98.4% (±25.0%) |
| e1-pg-uniform@100k | r1-base | steady | **p99 > 10× p50** | p50 20.7 ms, p99 2089.0 ms |
| e1-pg-uniform@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -39.8% (±25.0%) |
| e1-pg-uniform@100k | r1-head | steady | **p99 > 10× p50** | p50 2.4 ms, p99 110.6 ms |
| e1-pg-uniform@100k | r2-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 166.9 ms |
| e1-pg-uniform@100k | r2-base | steady | **load generator saturated** | 0.90 of 1 cores: the run measured the generator |
| e1-pg-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -90.4% (±25.0%) |
| e1-pg-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -99.5% (±25.0%) |
| e1-pg-uniform@100k | r3-base | steady | **p99 > 10× p50** | p50 172.0 ms, p99 3358.7 ms |
| e1-pg-uniform@100k | r3-head | steady | **p99 > 10× p50** | p50 2.6 ms, p99 140.3 ms |
| e1-pg-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -99.1% (±25.0%) |
| e1-pg-uniform@100k | e1-base | steady | **p99 > 10× p50** | p50 72.7 ms, p99 2424.8 ms |
| e1-pg-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.5% (±25.0%) |
| e1-pg-uniform@100k | e1-head | steady | **p99 > 10× p50** | p50 2.4 ms, p99 149.5 ms |
| e1-pg-uniform@100k | e2-head | steady | **p99 > 10× p50** | p50 1.5 ms, p99 106.5 ms |
| e1-pg-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -89.6% (±25.0%) |

