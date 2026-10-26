## large-pg@1k

E1 with a large payload on PostgreSQL, as large-mongo.

E1-large, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1143.9/s | 64 | 386.1/s | 34% |
| head | 1288.1/s | 512 | 579.7/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 915.1/s | 176.2/s | 3523 | 14778 | 13434.9 ms | 16515.1 ms | 17694.7 ms | n/a | 20124.1 ms | 1.72 cores | kafka 76% · postgres 41% | 0.91 of 1 | server 1.72 | 16.41 | 138.3 KB | http-429-wow.command.busy 11390 · http-504-wow.wait.timeout 3388 |
| probe2-base | base | 686.3/s | 297.0/s | 5940 | 7786 | 8126.5 ms | 12189.7 ms | 13238.3 ms | n/a | 13893.6 ms | 1.59 cores | kafka 75% · postgres 53% | 0.81 of 1 | server 1.59 | 9.65 | 205.2 KB | http-429-wow.command.busy 5446 · http-504-wow.wait.timeout 2340 |
| probe3-base | base | 514.7/s | 459.8/s | 9196 | 1097 | 4194.3 ms | 9961.5 ms | 10944.5 ms | n/a | 12124.2 ms | 1.52 cores | kafka 78% · postgres 62% | 0.70 of 1 | server 1.52 | 6.34 | 292.5 KB | http-504-wow.wait.timeout 1097 |
| probe4-base | base | 386.1/s | 385.9/s | 7719 | 3 | 1204.2 ms | 4784.1 ms | 7503.9 ms | n/a | 10747.9 ms | 1.49 cores | kafka 79% · postgres 60% | 0.65 of 1 | server 1.49 | 7.48 | 329.1 KB | http-504-wow.wait.timeout 3 |
| probe1-head | head | 1030.5/s | 474.1/s | 9483 | 11127 | 14549.0 ms | 18612.2 ms | 19791.9 ms | n/a | 21495.8 ms | 1.70 cores | kafka 74% · postgres 43% | 0.93 of 1 | server 1.70 | 6.07 | 148.5 KB | http-429-wow.command.busy 9932 · http-504-wow.wait.timeout 1195 |
| probe2-head | head | 772.9/s | 540.5/s | 10810 | 4648 | 7602.2 ms | 11665.4 ms | 12779.5 ms | n/a | 13631.5 ms | 1.59 cores | kafka 81% · postgres 52% | 0.86 of 1 | server 1.59 | 5.40 | 205.1 KB | http-429-wow.command.busy 4395 · http-504-wow.wait.timeout 253 |
| probe3-head | head | 579.7/s | 579.6/s | 11593 | 1 | 3833.9 ms | 6815.7 ms | 8060.9 ms | n/a | 10289.2 ms | 1.51 cores | kafka 83% · postgres 58% | 0.70 of 1 | server 1.51 | 5.04 | 294.8 KB | http-504-wow.wait.timeout 1 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 386.1/s | 386.1/s | 15444 | 0 | 3.2 ms | 44.0 ms | 112.6 ms | n/a | 261.1 ms | 1.23 cores | kafka 59% · postgres 51% | 0.28 of 1 | server 1.23 | 6.03 | 262.1 KB | - |
| r1-head | head | 579.7/s | 579.7/s | 23188 | 0 | 58.1 ms | 733.2 ms | 1540.1 ms | n/a | 2424.8 ms | 1.54 cores | kafka 55% · postgres 80% | 0.39 of 1 | server 1.54 | 4.98 | 243.8 KB | - |
| r2-head | head | 579.7/s | 579.7/s | 23188 | 0 | 58.9 ms | 536.6 ms | 974.8 ms | n/a | 1679.4 ms | 1.52 cores | kafka 53% · postgres 81% | 0.38 of 1 | server 1.52 | 4.93 | 245.6 KB | - |
| r2-base | base | 386.1/s | 386.1/s | 15444 | 0 | 3.3 ms | 24.6 ms | 69.1 ms | n/a | 146.4 ms | 1.21 cores | kafka 60% · postgres 52% | 0.28 of 1 | server 1.21 | 6.01 | 261.8 KB | - |
| r3-base | base | 386.1/s | 386.1/s | 15444 | 0 | 3.0 ms | 89.1 ms | 193.5 ms | n/a | 634.9 ms | 1.18 cores | kafka 62% · postgres 51% | 0.26 of 1 | server 1.18 | 5.96 | 261.8 KB | - |
| r3-head | head | 579.7/s | 579.7/s | 23188 | 0 | 64.5 ms | 462.8 ms | 806.9 ms | n/a | 1400.8 ms | 1.53 cores | kafka 53% · postgres 81% | 0.38 of 1 | server 1.53 | 4.94 | 242.2 KB | - |
| e1-base | base | 386.1/s | 386.1/s | 15444 | 0 | 2.8 ms | 23.2 ms | 72.7 ms | n/a | 176.1 ms | 1.08 cores | kafka 61% · postgres 50% | 0.30 of 1 | server 1.08 | 5.65 | 260.3 KB | - |
| e1-head | head | 386.1/s | 386.1/s | 15444 | 0 | 2.9 ms | 37.9 ms | 106.5 ms | n/a | 284.7 ms | 1.12 cores | kafka 63% · postgres 51% | 0.28 of 1 | server 1.12 | 5.85 | 240.1 KB | - |
| e2-head | head | 386.1/s | 386.1/s | 15444 | 0 | 3.2 ms | 29.8 ms | 94.2 ms | n/a | 331.8 ms | 1.19 cores | kafka 62% · postgres 52% | 0.30 of 1 | server 1.19 | 6.03 | 240.5 KB | - |
| e2-base | base | 386.1/s | 386.1/s | 15444 | 0 | 3.2 ms | 56.8 ms | 178.2 ms | n/a | 348.2 ms | 1.17 cores | kafka 59% · postgres 50% | 0.30 of 1 | server 1.17 | 5.87 | 260.6 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.501 | 18.257 | 16.652 | 13.673 | - | 9.286 | 0.826 | 0.930 |
| 2 | 1.501 | 17.869 | 21.834 | 14.104 | - | 11.469 | 0.820 | 0.938 |
| 3 | 1.501 | 21.568 | 5.195 | 4.169 | - | 2.206 | 0.829 | 0.925 |

Pairs at one rate for both arms, 386.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.022 | 1.635 | 1.465 | - | 1.616 | 1.035 | 0.922 |
| 2 | 1.000 | 1.005 | 0.525 | 0.529 | - | 0.953 | 1.028 | 0.923 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -46.5% (±25.0%) |
| large-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 3.2 ms, p99 112.6 ms |
| large-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -82.2% (±25.0%) |
| large-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 58.1 ms, p99 1540.1 ms |
| large-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -74.9% (±25.0%) |
| large-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 58.9 ms, p99 974.8 ms |
| large-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -44.1% (±25.0%) |
| large-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 3.3 ms, p99 69.1 ms |
| large-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -46.8% (±25.0%) |
| large-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 3.0 ms, p99 193.5 ms |
| large-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -73.0% (±25.0%) |
| large-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 64.5 ms, p99 806.9 ms |
| large-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -43.8% (±25.0%) |
| large-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 2.8 ms, p99 72.7 ms |
| large-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -40.1% (±25.0%) |
| large-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 2.9 ms, p99 106.5 ms |
| large-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -48.6% (±25.0%) |
| large-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 3.2 ms, p99 94.2 ms |
| large-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -62.8% (±25.0%) |
| large-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 3.2 ms, p99 178.2 ms |
| large-pg@1k | probe1-base | overload (probe) | **tail read the store** | gap=24 |
| large-pg@1k | probe2-base | overload (probe) | **tail read the store** | gap=9 |
| large-pg@1k | probe3-base | overload (probe) | **tail read the store** | gap=3 |
| large-pg@1k | probe4-base | overload (probe) | **tail read the store** | gap=3 |

