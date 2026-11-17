## cliff-e3-mongo@100k

E3 on MongoDB in fixed open-loop steps, as cliff-e3-pg.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: Intel(R) Xeon(R) 6975P-C, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (24–25 s), then waited until the nodes were quiet again (3–7 s).

- `head`: build e3a4e937aa

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 5.0 ms | 14.8 ms | 36.1 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 4.1 ms | 19.8 ms | 62.5 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 4.0 ms | 34.0 ms | 122.9 ms | - | tailRead idle=1 |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 5.1 ms | 38.1 ms | 91.6 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 7.2 ms | 40.7 ms | 66.6 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 8.4 ms | 62.5 ms | 174.1 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 5.0 ms | 10.0 ms | 14.8 ms | n/a | 36.1 ms | 0.22 cores | kafka 44% · mongo 45% | 0.07 of 1 | mongo 0.45 | 22.31 | 483.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 4.1 ms | 9.8 ms | 19.8 ms | n/a | 62.5 ms | 0.32 cores | kafka 44% · mongo 63% | 0.10 of 1 | mongo 0.63 | 13.93 | 410.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 4.0 ms | 17.5 ms | 34.0 ms | n/a | 122.9 ms | 0.55 cores | kafka 44% · mongo 95% | 0.13 of 1 | mongo 0.95 | 9.70 | 359.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 5.1 ms | 22.7 ms | 38.1 ms | n/a | 91.6 ms | 0.68 cores | kafka 47% · mongo 118% | 0.16 of 1 | mongo 1.18 | 7.77 | 328.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 7.2 ms | 26.4 ms | 40.7 ms | n/a | 66.6 ms | 0.75 cores | kafka 46% · mongo 129% | 0.18 of 1 | mongo 1.29 | 6.26 | 300.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 8.4 ms | 36.9 ms | 62.5 ms | n/a | 174.1 ms | 0.79 cores | kafka 43% · mongo 127% | 0.22 of 1 | mongo 1.27 | 4.97 | 270.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

