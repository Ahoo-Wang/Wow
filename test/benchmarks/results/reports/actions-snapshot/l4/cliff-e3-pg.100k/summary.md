## cliff-e3-pg@100k

E3 on PostgreSQL in fixed open-loop steps: where completions stop following the rate, and whether the tails fell back to the store (a cliff) or the node refused work (overload).

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: Intel(R) Xeon(R) 6975P-C, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (20–24 s), then waited until the nodes were quiet again (3–5 s).

- `head`: build e3a4e937aa

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 2.7 ms | 7.6 ms | 31.5 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 2.1 ms | 6.7 ms | 36.6 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 1.8 ms | 6.4 ms | 52.2 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 1.7 ms | 9.2 ms | 52.2 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 1.6 ms | 11.1 ms | 48.4 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 1.7 ms | 14.2 ms | 58.4 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 2.7 ms | 5.5 ms | 7.6 ms | n/a | 31.5 ms | 0.21 cores | kafka 42% · postgres 10% | 0.07 of 1 | kafka 0.42 | 14.70 | 276.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 2.1 ms | 4.7 ms | 6.7 ms | n/a | 36.6 ms | 0.32 cores | kafka 46% · postgres 17% | 0.09 of 1 | kafka 0.46 | 9.42 | 252.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 1.8 ms | 4.0 ms | 6.4 ms | n/a | 52.2 ms | 0.52 cores | kafka 46% · postgres 28% | 0.12 of 1 | server 0.52 | 6.33 | 233.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 1.7 ms | 4.6 ms | 9.2 ms | n/a | 52.2 ms | 0.75 cores | kafka 48% · postgres 42% | 0.15 of 1 | server 0.75 | 5.51 | 229.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 1.6 ms | 5.3 ms | 11.1 ms | n/a | 48.4 ms | 0.90 cores | kafka 49% · postgres 52% | 0.17 of 1 | server 0.90 | 4.77 | 221.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 1.7 ms | 7.4 ms | 14.2 ms | n/a | 58.4 ms | 1.06 cores | kafka 47% · postgres 62% | 0.20 of 1 | server 1.06 | 4.30 | 218.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |

