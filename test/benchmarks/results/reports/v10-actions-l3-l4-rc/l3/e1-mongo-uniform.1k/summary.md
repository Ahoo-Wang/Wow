## e1-mongo-uniform@1k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2087.4/s | 1024 | 939.3/s | 45% |
| head | 3727.6/s | 128 | 1677.4/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1669.9/s | 859.1/s | 17182 | 16215 | 17956.9 ms | 19529.7 ms | 19922.9 ms | n/a | 20185.1 ms | 1.38 cores | kafka 63% · mongo 84% | 0.92 of 1 | server 1.38 | 3.31 | 86.1 KB | generator-overload 13223 · exception-ConnectException 2662 · http-429-wow.command.busy 330 |
| probe2-base | base | 1252.4/s | 1179.0/s | 23579 | 1468 | 2277.4 ms | 6160.4 ms | 7766.0 ms | n/a | 9764.9 ms | 1.46 cores | kafka 49% · mongo 91% | 0.80 of 1 | server 1.46 | 2.42 | 148.1 KB | http-429-wow.command.busy 1468 |
| probe3-base | base | 939.3/s | 939.3/s | 18786 | 0 | 362.5 ms | 2072.6 ms | 3604.5 ms | n/a | 5406.7 ms | 1.48 cores | kafka 46% · mongo 97% | 0.54 of 1 | server 1.48 | 3.11 | 189.3 KB | - |
| probe1-head | head | 2982.1/s | 1182.6/s | 23652 | 35990 | 12714.0 ms | 15007.7 ms | 17563.6 ms | n/a | 17956.9 ms | 1.26 cores | kafka 54% · mongo 65% | 0.96 of 1 | server 1.26 | 2.07 | 39.8 KB | generator-overload 34374 · exception-ConnectException 1616 |
| probe2-head | head | 2236.6/s | 1577.9/s | 31558 | 13174 | 9568.3 ms | 15794.2 ms | 17039.4 ms | n/a | 17301.5 ms | 1.37 cores | kafka 49% · mongo 74% | 0.96 of 1 | server 1.37 | 1.65 | 72.2 KB | generator-overload 13174 |
| probe3-head | head | 1677.4/s | 1677.4/s | 33548 | 0 | 4358.1 ms | 5210.1 ms | 6619.1 ms | n/a | 6914.0 ms | 1.46 cores | kafka 49% · mongo 78% | 0.95 of 1 | server 1.46 | 1.63 | 105.3 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 939.3/s | 939.3/s | 37572 | 0 | 48.9 ms | 344.1 ms | 614.4 ms | n/a | 1245.2 ms | 1.21 cores | kafka 38% · mongo 126% | 0.38 of 1 | mongo 1.26 | 3.03 | 212.9 KB | - |
| r1-head | head | 1677.4/s | 1677.4/s | 67096 | 0 | 12.7 ms | 1105.9 ms | 1826.8 ms | n/a | 2023.4 ms | 1.14 cores | kafka 49% · mongo 109% | 0.66 of 1 | server 1.14 | 1.62 | 110.9 KB | - |
| r2-head | head | 1677.4/s | 1677.4/s | 67096 | 0 | 19.7 ms | 544.8 ms | 864.3 ms | n/a | 1114.1 ms | 1.16 cores | kafka 49% · mongo 106% | 0.69 of 1 | server 1.16 | 1.61 | 102.5 KB | - |
| r2-base | base | 939.3/s | 939.3/s | 37572 | 0 | 79.4 ms | 536.6 ms | 925.7 ms | n/a | 1777.7 ms | 1.26 cores | kafka 38% · mongo 121% | 0.40 of 1 | server 1.26 | 3.03 | 203.0 KB | - |
| r3-base | base | 939.3/s | 939.3/s | 37572 | 0 | 26.9 ms | 243.7 ms | 460.8 ms | n/a | 925.7 ms | 1.07 cores | kafka 40% · mongo 130% | 0.38 of 1 | mongo 1.30 | 2.95 | 215.9 KB | - |
| r3-head | head | 1677.4/s | 1677.4/s | 67096 | 0 | 7.3 ms | 150.5 ms | 337.9 ms | n/a | 536.6 ms | 1.05 cores | kafka 47% · mongo 112% | 0.60 of 1 | mongo 1.12 | 1.57 | 111.3 KB | - |
| e1-base | base | 939.3/s | 939.3/s | 37572 | 0 | 50.4 ms | 342.0 ms | 581.6 ms | n/a | 1179.6 ms | 1.18 cores | kafka 37% · mongo 126% | 0.37 of 1 | mongo 1.26 | 2.99 | 209.5 KB | - |
| e1-head | head | 939.3/s | 939.3/s | 37572 | 0 | 2.9 ms | 35.3 ms | 126.5 ms | n/a | 234.5 ms | 0.85 cores | kafka 44% · mongo 97% | 0.35 of 1 | mongo 0.97 | 2.42 | 130.7 KB | - |
| e2-head | head | 939.3/s | 939.3/s | 37572 | 0 | 3.5 ms | 44.0 ms | 153.6 ms | n/a | 376.8 ms | 0.88 cores | kafka 44% · mongo 95% | 0.34 of 1 | mongo 0.95 | 2.42 | 129.1 KB | - |
| e2-base | base | 939.3/s | 939.3/s | 37572 | 0 | 46.3 ms | 536.6 ms | 1003.5 ms | n/a | 1630.2 ms | 1.22 cores | kafka 38% · mongo 126% | 0.38 of 1 | mongo 1.26 | 3.05 | 208.8 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.786 | 0.259 | 3.214 | 2.973 | - | 1.625 | 0.533 | 0.521 |
| 2 | 1.786 | 0.248 | 1.015 | 0.934 | - | 0.627 | 0.532 | 0.505 |
| 3 | 1.786 | 0.273 | 0.618 | 0.733 | - | 0.580 | 0.533 | 0.516 |

Pairs at one rate for both arms, 939.3/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.057 | 0.103 | 0.217 | - | 0.199 | 0.808 | 0.624 |
| 2 | 1.000 | 0.075 | 0.082 | 0.153 | - | 0.231 | 0.796 | 0.618 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -75.7% (±25.0%) |
| e1-mongo-uniform@1k | r1-base | steady | **p99 > 10× p50** | p50 48.9 ms, p99 614.4 ms |
| e1-mongo-uniform@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.1% (±25.0%) |
| e1-mongo-uniform@1k | r1-head | steady | **p99 > 10× p50** | p50 12.7 ms, p99 1826.8 ms |
| e1-mongo-uniform@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -65.9% (±25.0%) |
| e1-mongo-uniform@1k | r2-head | steady | **p99 > 10× p50** | p50 19.7 ms, p99 864.3 ms |
| e1-mongo-uniform@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -83.8% (±25.0%) |
| e1-mongo-uniform@1k | r2-base | steady | **p99 > 10× p50** | p50 79.4 ms, p99 925.7 ms |
| e1-mongo-uniform@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -67.3% (±25.0%) |
| e1-mongo-uniform@1k | r3-base | steady | **p99 > 10× p50** | p50 26.9 ms, p99 460.8 ms |
| e1-mongo-uniform@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -50.5% (±25.0%) |
| e1-mongo-uniform@1k | r3-head | steady | **p99 > 10× p50** | p50 7.3 ms, p99 337.9 ms |
| e1-mongo-uniform@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.7% (±25.0%) |
| e1-mongo-uniform@1k | e1-base | steady | **p99 > 10× p50** | p50 50.4 ms, p99 581.6 ms |
| e1-mongo-uniform@1k | e1-head | steady | **p99 > 10× p50** | p50 2.9 ms, p99 126.5 ms |
| e1-mongo-uniform@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.5% (±25.0%) |
| e1-mongo-uniform@1k | e2-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 153.6 ms |
| e1-mongo-uniform@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -69.6% (±25.0%) |
| e1-mongo-uniform@1k | e2-base | steady | **p99 > 10× p50** | p50 46.3 ms, p99 1003.5 ms |

