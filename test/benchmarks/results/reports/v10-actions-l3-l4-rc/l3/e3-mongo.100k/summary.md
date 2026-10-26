## e3-mongo@100k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (56–163 s), then waited until the nodes were quiet again (4–7 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 518.0/s | 2048 | 310.8/s | 60% |
| head | 976.8/s | 2048 | 586.1/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 414.4/s | 386.4/s | 7729 | 559 | 6783.0 ms | 9437.2 ms | 10027.0 ms | n/a | 10289.2 ms | 1.28 cores | kafka 37% · mongo 130% | 0.52 of 1 | mongo 1.30 | 7.61 | 425.4 KB | http-504-wow.wait.timeout 407 · http-429-wow.command.busy 152 |
| probe2-base | base | 310.8/s | 310.8/s | 6216 | 0 | 1138.7 ms | 2179.1 ms | 2654.2 ms | n/a | 2981.9 ms | 0.95 cores | kafka 46% · mongo 151% | 0.43 of 1 | mongo 1.51 | 9.38 | 572.4 KB | - |
| probe1-head | head | 781.4/s | 770.6/s | 15413 | 215 | 3538.9 ms | 4620.3 ms | 5111.8 ms | n/a | 5406.7 ms | 1.20 cores | kafka 49% · mongo 122% | 0.86 of 1 | mongo 1.22 | 3.77 | 220.0 KB | http-429-wow.command.busy 215 |
| probe2-head | head | 586.1/s | 586.1/s | 11722 | 0 | 104.4 ms | 581.6 ms | 921.6 ms | n/a | 1171.5 ms | 0.89 cores | kafka 49% · mongo 147% | 0.64 of 1 | mongo 1.47 | 4.86 | 252.9 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 310.8/s | 310.8/s | 12432 | 0 | 876.5 ms | 1531.9 ms | 1916.9 ms | n/a | 2474.0 ms | 0.85 cores | kafka 44% · mongo 166% | 0.26 of 1 | mongo 1.66 | 9.47 | 559.8 KB | - |
| r1-head | head | 586.1/s | 586.1/s | 23444 | 0 | 41.2 ms | 280.6 ms | 389.1 ms | n/a | 493.6 ms | 0.78 cores | kafka 52% · mongo 156% | 0.33 of 1 | mongo 1.56 | 4.88 | 250.8 KB | - |
| r2-head | head | 586.1/s | 586.1/s | 23444 | 0 | 39.4 ms | 272.4 ms | 454.7 ms | n/a | 774.1 ms | 0.77 cores | kafka 52% · mongo 156% | 0.33 of 1 | mongo 1.56 | 4.87 | 253.2 KB | - |
| r2-base | base | 310.8/s | 310.8/s | 12432 | 0 | 798.7 ms | 1458.2 ms | 1753.1 ms | n/a | 2211.8 ms | 0.86 cores | kafka 41% · mongo 167% | 0.24 of 1 | mongo 1.67 | 9.48 | 574.4 KB | - |
| r3-base | base | 310.8/s | 310.8/s | 12432 | 0 | 802.8 ms | 1490.9 ms | 1785.9 ms | n/a | 2474.0 ms | 0.85 cores | kafka 43% · mongo 166% | 0.24 of 1 | mongo 1.66 | 9.48 | 569.2 KB | - |
| r3-head | head | 586.1/s | 586.1/s | 23444 | 0 | 44.8 ms | 247.8 ms | 378.9 ms | n/a | 655.4 ms | 0.77 cores | kafka 45% · mongo 160% | 0.35 of 1 | mongo 1.60 | 4.83 | 248.5 KB | - |
| e1-base | base | 310.8/s | 310.8/s | 12432 | 0 | 733.2 ms | 1318.9 ms | 1589.2 ms | n/a | 2392.1 ms | 0.88 cores | kafka 41% · mongo 166% | 0.26 of 1 | mongo 1.66 | 9.49 | 585.4 KB | - |
| e1-head | head | 310.8/s | 310.8/s | 12432 | 0 | 11.5 ms | 79.9 ms | 153.6 ms | n/a | 276.5 ms | 0.68 cores | kafka 53% · mongo 145% | 0.22 of 1 | mongo 1.45 | 8.56 | 331.9 KB | - |
| e2-head | head | 310.8/s | 310.8/s | 12432 | 0 | 10.8 ms | 68.6 ms | 136.2 ms | n/a | 236.5 ms | 0.70 cores | kafka 52% · mongo 143% | 0.22 of 1 | mongo 1.43 | 8.53 | 335.5 KB | - |
| e2-base | base | 310.8/s | 310.8/s | 12432 | 0 | 798.7 ms | 1400.8 ms | 1646.6 ms | n/a | 1916.9 ms | 0.85 cores | kafka 39% · mongo 170% | 0.24 of 1 | mongo 1.70 | 9.46 | 571.8 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.886 | 0.047 | 0.183 | 0.203 | - | 0.200 | 0.515 | 0.448 |
| 2 | 1.886 | 0.049 | 0.187 | 0.259 | - | 0.350 | 0.514 | 0.441 |
| 3 | 1.886 | 0.056 | 0.166 | 0.212 | - | 0.265 | 0.509 | 0.437 |

Pairs at one rate for both arms, 310.8/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.016 | 0.061 | 0.097 | - | 0.116 | 0.903 | 0.567 |
| 2 | 1.000 | 0.014 | 0.049 | 0.083 | - | 0.123 | 0.903 | 0.587 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -28.4% (±25.0%) |
| e3-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 39.4 ms, p99 454.7 ms |
| e3-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.6% (±25.0%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +28.5% (±25.0%) |
| e3-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 11.5 ms, p99 153.6 ms |
| e3-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 10.8 ms, p99 136.2 ms |

