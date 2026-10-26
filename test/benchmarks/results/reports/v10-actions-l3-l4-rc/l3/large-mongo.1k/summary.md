## large-mongo@1k

E1 with a large payload on MongoDB (D224 note ⑥): five events of about 2 KB per command. Head against baseline.

E1-large, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1158.4/s | 2048 | 391.0/s | 34% |
| head | 1740.6/s | 2048 | 587.5/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 926.7/s | 120.7/s | 2414 | 16120 | 13107.2 ms | 16908.3 ms | 17694.7 ms | n/a | 18811.5 ms | 1.66 cores | kafka 67% · mongo 60% | 0.91 of 1 | server 1.66 | 24.30 | 138.8 KB | http-429-wow.command.busy 12263 · http-504-wow.wait.timeout 3857 |
| probe2-base | base | 695.0/s | 238.4/s | 4769 | 9131 | 9044.0 ms | 12320.8 ms | 12910.6 ms | n/a | 14015.6 ms | 1.56 cores | kafka 72% · mongo 61% | 0.85 of 1 | server 1.56 | 12.11 | 181.0 KB | http-429-wow.command.busy 6795 · http-504-wow.wait.timeout 2336 |
| probe3-base | base | 521.3/s | 362.4/s | 7248 | 3177 | 7569.4 ms | 11141.1 ms | 11993.1 ms | n/a | 13303.8 ms | 1.54 cores | kafka 74% · mongo 65% | 0.75 of 1 | server 1.54 | 8.09 | 254.6 KB | http-504-wow.wait.timeout 1910 · http-429-wow.command.busy 1267 |
| probe4-base | base | 391.0/s | 388.4/s | 7767 | 53 | 2916.4 ms | 8028.2 ms | 9895.9 ms | n/a | 11337.7 ms | 1.38 cores | kafka 76% · mongo 75% | 0.64 of 1 | server 1.38 | 7.45 | 345.8 KB | http-504-wow.wait.timeout 53 |
| probe1-head | head | 1392.5/s | 706.5/s | 14130 | 13720 | 17301.5 ms | 24117.2 ms | 24379.4 ms | n/a | 25236.9 ms | 1.48 cores | kafka 81% · mongo 57% | 0.92 of 1 | server 1.48 | 4.05 | 105.6 KB | generator-overload 8228 · exception-ConnectException 5492 |
| probe2-head | head | 1044.4/s | 530.5/s | 10611 | 10277 | 14942.2 ms | 18481.2 ms | 19529.7 ms | n/a | 20840.4 ms | 1.69 cores | kafka 74% · mongo 47% | 0.94 of 1 | server 1.69 | 5.48 | 142.7 KB | http-429-wow.command.busy 9303 · http-504-wow.wait.timeout 974 |
| probe3-head | head | 783.3/s | 577.1/s | 11542 | 4124 | 7831.6 ms | 11403.3 ms | 12517.4 ms | n/a | 14680.1 ms | 1.65 cores | kafka 76% · mongo 51% | 0.92 of 1 | server 1.65 | 5.07 | 201.5 KB | http-429-wow.command.busy 4051 · http-504-wow.wait.timeout 73 |
| probe4-head | head | 587.5/s | 582.5/s | 11651 | 99 | 4227.1 ms | 6979.6 ms | 8060.9 ms | n/a | 9764.9 ms | 1.52 cores | kafka 84% · mongo 56% | 0.81 of 1 | server 1.52 | 5.02 | 280.4 KB | http-429-wow.command.busy 99 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 391.0/s | 391.0/s | 15640 | 0 | 315.4 ms | 1024.0 ms | 1458.2 ms | n/a | 2703.4 ms | 1.37 cores | kafka 52% · mongo 104% | 0.33 of 1 | server 1.37 | 7.47 | 354.0 KB | - |
| r1-head | head | 587.5/s | 587.5/s | 23500 | 0 | 58.9 ms | 512.0 ms | 958.5 ms | n/a | 1523.7 ms | 1.42 cores | kafka 55% · mongo 94% | 0.40 of 1 | server 1.42 | 4.97 | 251.3 KB | - |
| r2-head | head | 587.5/s | 587.5/s | 23500 | 0 | 82.4 ms | 1826.8 ms | 2949.1 ms | n/a | 3817.5 ms | 1.46 cores | kafka 58% · mongo 89% | 0.46 of 1 | server 1.46 | 4.99 | 263.5 KB | - |
| r2-base | base | 391.0/s | 391.0/s | 15640 | 0 | 340.0 ms | 1179.6 ms | 1695.7 ms | n/a | 2768.9 ms | 1.33 cores | kafka 52% · mongo 105% | 0.38 of 1 | server 1.33 | 7.42 | 346.7 KB | - |
| r3-base | base | 391.0/s | 391.0/s | 15640 | 0 | 270.3 ms | 1097.7 ms | 1720.3 ms | n/a | 3211.3 ms | 1.39 cores | kafka 49% · mongo 106% | 0.33 of 1 | server 1.39 | 7.54 | 365.0 KB | - |
| r3-head | head | 587.5/s | 587.5/s | 23500 | 0 | 91.6 ms | 806.9 ms | 1228.8 ms | n/a | 1998.8 ms | 1.42 cores | kafka 60% · mongo 92% | 0.44 of 1 | server 1.42 | 5.02 | 246.4 KB | - |
| e1-base | base | 391.0/s | 391.0/s | 15640 | 0 | 593.9 ms | 3162.1 ms | 5308.4 ms | n/a | 8585.2 ms | 1.39 cores | kafka 53% · mongo 100% | 0.38 of 1 | server 1.39 | 7.49 | 348.5 KB | - |
| e1-head | head | 391.0/s | 391.0/s | 15640 | 0 | 21.4 ms | 233.5 ms | 380.9 ms | n/a | 577.5 ms | 1.39 cores | kafka 60% · mongo 91% | 0.34 of 1 | server 1.39 | 7.43 | 266.4 KB | - |
| e2-head | head | 391.0/s | 391.0/s | 15640 | 0 | 19.2 ms | 329.7 ms | 557.1 ms | n/a | 786.4 ms | 1.42 cores | kafka 57% · mongo 90% | 0.34 of 1 | server 1.42 | 7.41 | 265.7 KB | - |
| e2-base | base | 391.0/s | 391.0/s | 15640 | 0 | 250.9 ms | 1065.0 ms | 1564.7 ms | n/a | 2588.7 ms | 1.38 cores | kafka 49% · mongo 107% | 0.32 of 1 | server 1.38 | 7.51 | 361.3 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.503 | 0.187 | 0.500 | 0.657 | - | 0.564 | 0.665 | 0.710 |
| 2 | 1.503 | 0.242 | 1.549 | 1.739 | - | 1.379 | 0.673 | 0.760 |
| 3 | 1.503 | 0.339 | 0.735 | 0.714 | - | 0.622 | 0.665 | 0.675 |

Pairs at one rate for both arms, 391.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.036 | 0.074 | 0.072 | - | 0.067 | 0.993 | 0.764 |
| 2 | 1.000 | 0.077 | 0.310 | 0.356 | - | 0.304 | 0.986 | 0.735 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -34.2% (±25.0%) |
| large-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -30.7% (±25.0%) |
| large-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 58.9 ms, p99 958.5 ms |
| large-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -70.1% (±25.0%) |
| large-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 82.4 ms, p99 2949.1 ms |
| large-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -31.3% (±25.0%) |
| large-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -50.0% (±25.0%) |
| large-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -63.4% (±25.0%) |
| large-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 91.6 ms, p99 1228.8 ms |
| large-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -60.6% (±25.0%) |
| large-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -41.7% (±25.0%) |
| large-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 21.4 ms, p99 380.9 ms |
| large-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.9% (±25.0%) |
| large-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 19.2 ms, p99 557.1 ms |
| large-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.7% (±25.0%) |

