## cliff-e3-pg@100k

E3 on PostgreSQL in fixed open-loop steps: where completions stop following the rate, and whether the tails fell back to the store (a cliff) or the node refused work (overload).

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (31–32 s), then waited until the nodes were quiet again (3–4 s).

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 2.8 ms | 13.3 ms | 212.2 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 2.3 ms | 21.1 ms | 236.5 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 2.0 ms | 56.6 ms | 261.1 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 2.0 ms | 74.2 ms | 323.6 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 2.2 ms | 103.9 ms | 333.8 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 3.2 ms | 137.2 ms | 325.6 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 2.8 ms | 6.6 ms | 13.3 ms | n/a | 212.2 ms | 0.19 cores | kafka 45% · postgres 12% | 0.07 of 1 | kafka 0.45 | 15.34 | 317.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 2.3 ms | 6.1 ms | 21.1 ms | n/a | 236.5 ms | 0.33 cores | kafka 48% · postgres 20% | 0.11 of 1 | kafka 0.48 | 10.11 | 294.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 2.0 ms | 6.1 ms | 56.6 ms | n/a | 261.1 ms | 0.53 cores | kafka 52% · postgres 35% | 0.15 of 1 | server 0.53 | 7.01 | 278.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 2.0 ms | 9.7 ms | 74.2 ms | n/a | 323.6 ms | 0.73 cores | kafka 53% · postgres 49% | 0.18 of 1 | server 0.73 | 5.85 | 269.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 2.2 ms | 18.4 ms | 103.9 ms | n/a | 333.8 ms | 0.90 cores | kafka 54% · postgres 63% | 0.21 of 1 | server 0.90 | 5.17 | 264.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 3.2 ms | 25.5 ms | 137.2 ms | n/a | 325.6 ms | 0.99 cores | kafka 53% · postgres 77% | 0.22 of 1 | server 0.99 | 4.59 | 267.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cliff-e3-pg@100k | s3-200 | steady | **p99 > 10× p50** | p50 2.0 ms, p99 56.6 ms |
| cliff-e3-pg@100k | s4-300 | steady | **p99 > 10× p50** | p50 2.0 ms, p99 74.2 ms |
| cliff-e3-pg@100k | s5-400 | steady | **p99 > 10× p50** | p50 2.2 ms, p99 103.9 ms |
| cliff-e3-pg@100k | s6-500 | steady | **unstable** | second half against first, beyond the default limits: p50 -32.4% (±25.0%) |
| cliff-e3-pg@100k | s6-500 | steady | **p99 > 10× p50** | p50 3.2 ms, p99 137.2 ms |

