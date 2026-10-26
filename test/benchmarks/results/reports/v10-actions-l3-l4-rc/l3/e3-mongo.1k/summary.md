## e3-mongo@1k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 522.8/s | 2048 | 235.2/s | 45% |
| head | 949.5/s | 128 | 320.5/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 418.2/s | 30.4/s | 607 | 7757 | 8290.3 ms | 10485.8 ms | 10747.9 ms | n/a | 11148.5 ms | 1.49 cores | kafka 67% · mongo 78% | 0.57 of 1 | server 1.49 | 96.88 | 253.7 KB | http-504-wow.wait.timeout 7757 |
| probe2-base | base | 313.7/s | 278.6/s | 5573 | 701 | 4849.7 ms | 9109.5 ms | 10027.0 ms | n/a | 10682.4 ms | 1.37 cores | kafka 59% · mongo 97% | 0.47 of 1 | server 1.37 | 10.52 | 375.9 KB | http-504-wow.wait.timeout 701 |
| probe3-base | base | 235.2/s | 234.6/s | 4692 | 12 | 2408.4 ms | 6357.0 ms | 8716.3 ms | n/a | 10027.0 ms | 1.33 cores | kafka 63% · mongo 98% | 0.35 of 1 | server 1.33 | 12.56 | 446.9 KB | http-504-wow.wait.timeout 12 |
| probe1-head | head | 759.6/s | 0.7/s | 14 | 15178 | 10485.8 ms | 12696.5 ms | 12696.5 ms | n/a | 12696.5 ms | 1.59 cores | kafka 72% · mongo 59% | 0.81 of 1 | server 1.59 | 4144.96 | 147.8 KB | http-504-wow.wait.timeout 10314 · http-429-wow.command.busy 4864 |
| probe2-head | head | 569.7/s | 14.8/s | 296 | 11098 | 9371.6 ms | 10813.4 ms | 11206.7 ms | n/a | 11534.5 ms | 1.53 cores | kafka 69% · mongo 66% | 0.67 of 1 | server 1.53 | 195.03 | 238.0 KB | http-504-wow.wait.timeout 11077 · http-429-wow.command.busy 21 |
| probe3-head | head | 427.3/s | 410.1/s | 8201 | 345 | 4194.3 ms | 8847.4 ms | 9961.5 ms | n/a | 10485.8 ms | 1.57 cores | kafka 61% · mongo 80% | 0.59 of 1 | server 1.57 | 7.25 | 311.6 KB | http-504-wow.wait.timeout 345 |
| probe4-head | head | 320.5/s | 320.5/s | 6410 | 0 | 216.1 ms | 1892.4 ms | 2588.7 ms | n/a | 3686.4 ms | 1.38 cores | kafka 63% · mongo 91% | 0.40 of 1 | server 1.38 | 9.13 | 305.6 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 235.2/s | 235.2/s | 9408 | 0 | 585.7 ms | 1687.6 ms | 2392.1 ms | n/a | 2949.1 ms | 1.43 cores | kafka 38% · mongo 118% | 0.23 of 1 | server 1.43 | 12.71 | 507.3 KB | - |
| r1-head | head | 320.5/s | 320.5/s | 12820 | 0 | 33.8 ms | 348.2 ms | 565.2 ms | n/a | 921.6 ms | 1.33 cores | kafka 53% · mongo 106% | 0.26 of 1 | server 1.33 | 9.12 | 294.7 KB | - |
| r2-head | head | 320.5/s | 320.5/s | 12820 | 0 | 24.6 ms | 220.2 ms | 374.8 ms | n/a | 679.9 ms | 1.29 cores | kafka 47% · mongo 111% | 0.24 of 1 | server 1.29 | 8.94 | 302.1 KB | - |
| r2-base | base | 235.2/s | 235.2/s | 9408 | 0 | 544.8 ms | 1417.2 ms | 1957.9 ms | n/a | 3604.5 ms | 1.35 cores | kafka 43% · mongo 122% | 0.24 of 1 | server 1.35 | 12.74 | 538.5 KB | - |
| r3-base | base | 235.2/s | 235.2/s | 9408 | 0 | 573.4 ms | 1728.5 ms | 2555.9 ms | n/a | 3604.5 ms | 1.34 cores | kafka 44% · mongo 120% | 0.23 of 1 | server 1.34 | 12.66 | 516.6 KB | - |
| r3-head | head | 320.5/s | 320.5/s | 12820 | 0 | 30.0 ms | 335.9 ms | 548.9 ms | n/a | 872.4 ms | 1.33 cores | kafka 49% · mongo 108% | 0.26 of 1 | server 1.33 | 9.03 | 297.9 KB | - |
| e1-base | base | 235.2/s | 235.2/s | 9408 | 0 | 460.8 ms | 1417.2 ms | 2113.5 ms | n/a | 2949.1 ms | 1.31 cores | kafka 41% · mongo 124% | 0.23 of 1 | server 1.31 | 12.58 | 527.4 KB | - |
| e1-head | head | 235.2/s | 235.2/s | 9408 | 0 | 15.6 ms | 167.9 ms | 423.9 ms | n/a | 720.9 ms | 1.20 cores | kafka 54% · mongo 106% | 0.23 of 1 | server 1.20 | 11.88 | 343.5 KB | - |
| e2-head | head | 235.2/s | 235.2/s | 9408 | 0 | 15.6 ms | 139.3 ms | 250.9 ms | n/a | 477.2 ms | 1.22 cores | kafka 50% · mongo 106% | 0.27 of 1 | server 1.22 | 11.79 | 342.9 KB | - |
| e2-base | base | 235.2/s | 235.2/s | 9408 | 0 | 548.9 ms | 1359.9 ms | 1826.8 ms | n/a | 2457.6 ms | 1.38 cores | kafka 41% · mongo 120% | 0.22 of 1 | server 1.38 | 12.68 | 527.4 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.363 | 0.058 | 0.206 | 0.236 | - | 0.312 | 0.718 | 0.581 |
| 2 | 1.363 | 0.045 | 0.155 | 0.191 | - | 0.189 | 0.702 | 0.561 |
| 3 | 1.363 | 0.052 | 0.194 | 0.215 | - | 0.242 | 0.713 | 0.577 |

Pairs at one rate for both arms, 235.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.034 | 0.118 | 0.201 | - | 0.244 | 0.945 | 0.651 |
| 2 | 1.000 | 0.028 | 0.102 | 0.137 | - | 0.194 | 0.930 | 0.650 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -46.5% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -48.8% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 33.8 ms, p99 565.2 ms |
| e3-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -53.5% (±25.0%) |
| e3-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 24.6 ms, p99 374.8 ms |
| e3-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -36.8% (±25.0%) |
| e3-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -42.0% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -46.6% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 30.0 ms, p99 548.9 ms |
| e3-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -54.5% (±25.0%) |
| e3-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 15.6 ms, p99 423.9 ms |
| e3-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -35.6% (±25.0%) |
| e3-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 15.6 ms, p99 250.9 ms |
| e3-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -39.4% (±25.0%) |

