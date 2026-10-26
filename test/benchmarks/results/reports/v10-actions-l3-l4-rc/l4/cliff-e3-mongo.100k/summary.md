## cliff-e3-mongo@100k

E3 on MongoDB in fixed open-loop steps, as cliff-e3-pg.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (34–38 s), then waited until the nodes were quiet again (3–4 s).

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 4.5 ms | 17.2 ms | 95.0 ms | - | tailRead idle=3 |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 5.2 ms | 34.6 ms | 103.3 ms | - | tailRead idle=1 |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 4.7 ms | 60.4 ms | 141.3 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 4.4 ms | 110.1 ms | 219.1 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 9.3 ms | 166.9 ms | 383.0 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 9.9 ms | 182.3 ms | 301.1 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 4.5 ms | 9.2 ms | 17.2 ms | n/a | 95.0 ms | 0.16 cores | kafka 39% · mongo 39% | 0.07 of 1 | kafka 0.39 | 18.82 | 477.7 KB | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 5.2 ms | 12.5 ms | 34.6 ms | n/a | 103.3 ms | 0.33 cores | kafka 45% · mongo 64% | 0.14 of 1 | mongo 0.64 | 14.20 | 426.0 KB | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 4.7 ms | 23.6 ms | 60.4 ms | n/a | 141.3 ms | 0.54 cores | kafka 49% · mongo 95% | 0.22 of 1 | mongo 0.95 | 9.86 | 386.5 KB | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 4.4 ms | 33.0 ms | 110.1 ms | n/a | 219.1 ms | 0.55 cores | kafka 44% · mongo 110% | 0.20 of 1 | mongo 1.10 | 6.96 | 365.2 KB | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 9.3 ms | 75.3 ms | 166.9 ms | n/a | 383.0 ms | 0.64 cores | kafka 42% · mongo 116% | 0.27 of 1 | mongo 1.16 | 5.55 | 322.3 KB | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 9.9 ms | 74.8 ms | 182.3 ms | n/a | 301.1 ms | 0.68 cores | kafka 46% · mongo 134% | 0.29 of 1 | mongo 1.34 | 4.95 | 309.8 KB | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cliff-e3-mongo@100k | s3-200 | steady | **p99 > 10× p50** | p50 4.7 ms, p99 60.4 ms |
| cliff-e3-mongo@100k | s4-300 | steady | **p99 > 10× p50** | p50 4.4 ms, p99 110.1 ms |
| cliff-e3-mongo@100k | s5-400 | steady | **p99 > 10× p50** | p50 9.3 ms, p99 166.9 ms |
| cliff-e3-mongo@100k | s6-500 | steady | **p99 > 10× p50** | p50 9.9 ms, p99 182.3 ms |

