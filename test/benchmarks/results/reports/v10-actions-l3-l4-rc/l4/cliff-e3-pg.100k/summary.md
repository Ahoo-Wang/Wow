## cliff-e3-pg@100k

E3 on PostgreSQL in fixed open-loop steps: where completions stop following the rate, and whether the tails fell back to the store (a cliff) or the node refused work (overload).

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (51–54 s), then waited until the nodes were quiet again (4–4 s).

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 3.5 ms | 9.5 ms | 32.0 ms | - | tailRead idle=2 |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 3.6 ms | 12.3 ms | 60.7 ms | - | tailRead idle=1 |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 2.8 ms | 38.4 ms | 274.4 ms | - | tailRead idle=1 |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 2.8 ms | 27.3 ms | 127.0 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 4.1 ms | 59.9 ms | 256.0 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 7.1 ms | 64.5 ms | 213.0 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 3.5 ms | 6.9 ms | 9.5 ms | n/a | 32.0 ms | 0.21 cores | kafka 55% · postgres 17% | 0.09 of 1 | kafka 0.55 | 18.48 | 265.8 KB | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 3.6 ms | 6.9 ms | 12.3 ms | n/a | 60.7 ms | 0.41 cores | kafka 61% · postgres 29% | 0.17 of 1 | kafka 0.61 | 13.05 | 246.6 KB | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 2.8 ms | 7.7 ms | 38.4 ms | n/a | 274.4 ms | 0.65 cores | kafka 60% · postgres 49% | 0.23 of 1 | server 0.65 | 8.72 | 232.5 KB | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 2.8 ms | 9.7 ms | 27.3 ms | n/a | 127.0 ms | 0.80 cores | kafka 59% · postgres 66% | 0.26 of 1 | server 0.80 | 6.84 | 224.6 KB | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 4.1 ms | 17.9 ms | 59.9 ms | n/a | 256.0 ms | 0.95 cores | kafka 54% · postgres 83% | 0.31 of 1 | server 0.95 | 5.80 | 223.3 KB | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 7.1 ms | 25.2 ms | 64.5 ms | n/a | 213.0 ms | 1.02 cores | kafka 55% · postgres 98% | 0.36 of 1 | server 1.02 | 5.10 | 222.1 KB | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cliff-e3-pg@100k | s1-50 | steady | **unstable** | second half against first, beyond the default limits: p50 -25.7% (±25.0%) |
| cliff-e3-pg@100k | s5-400 | steady | **unstable** | second half against first, beyond the default limits: p50 -27.0% (±25.0%) |
| cliff-e3-pg@100k | s5-400 | steady | **p99 > 10× p50** | p50 4.1 ms, p99 59.9 ms |

