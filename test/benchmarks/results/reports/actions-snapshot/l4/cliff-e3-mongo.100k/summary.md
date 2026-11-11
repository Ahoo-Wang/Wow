## cliff-e3-mongo@100k

E3 on MongoDB in fixed open-loop steps, as cliff-e3-pg.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (27–30 s), then waited until the nodes were quiet again (3–4 s).

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 4.7 ms | 64.0 ms | 352.2 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 4.5 ms | 249.9 ms | 454.7 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 3.6 ms | 90.1 ms | 303.1 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 4.3 ms | 97.8 ms | 220.2 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 7.1 ms | 182.3 ms | 358.4 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 9.2 ms | 148.5 ms | 411.6 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 4.7 ms | 12.0 ms | 64.0 ms | n/a | 352.2 ms | 0.20 cores | kafka 42% · mongo 41% | 0.07 of 1 | kafka 0.42 | 20.58 | 542.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 4.5 ms | 16.6 ms | 249.9 ms | n/a | 454.7 ms | 0.32 cores | kafka 43% · mongo 57% | 0.12 of 1 | mongo 0.57 | 13.21 | 469.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 3.6 ms | 24.4 ms | 90.1 ms | n/a | 303.1 ms | 0.46 cores | kafka 45% · mongo 84% | 0.14 of 1 | mongo 0.84 | 8.72 | 425.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 4.3 ms | 29.7 ms | 97.8 ms | n/a | 220.2 ms | 0.59 cores | kafka 43% · mongo 100% | 0.16 of 1 | mongo 1.00 | 6.74 | 399.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 7.1 ms | 46.8 ms | 182.3 ms | n/a | 358.4 ms | 0.66 cores | kafka 44% · mongo 117% | 0.18 of 1 | mongo 1.17 | 5.66 | 369.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 9.2 ms | 57.3 ms | 148.5 ms | n/a | 411.6 ms | 0.72 cores | kafka 45% · mongo 132% | 0.21 of 1 | mongo 1.32 | 4.97 | 345.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cliff-e3-mongo@100k | s1-50 | steady | **p99 > 10× p50** | p50 4.7 ms, p99 64.0 ms |
| cliff-e3-mongo@100k | s2-100 | steady | **p99 > 10× p50** | p50 4.5 ms, p99 249.9 ms |
| cliff-e3-mongo@100k | s3-200 | steady | **p99 > 10× p50** | p50 3.6 ms, p99 90.1 ms |
| cliff-e3-mongo@100k | s4-300 | steady | **p99 > 10× p50** | p50 4.3 ms, p99 97.8 ms |
| cliff-e3-mongo@100k | s5-400 | steady | **p99 > 10× p50** | p50 7.1 ms, p99 182.3 ms |
| cliff-e3-mongo@100k | s6-500 | steady | **p99 > 10× p50** | p50 9.2 ms, p99 148.5 ms |

