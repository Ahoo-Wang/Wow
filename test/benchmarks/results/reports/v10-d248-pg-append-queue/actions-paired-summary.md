## pg-append-queue@100k

Whether PostgreSQL's append queue should be on by default (D248): saturation with it off and on, then both at the same offered load.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s). Runner: INTEL(R) XEON(R) PLATINUM 8573C, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (63–65 s), then waited until the nodes were quiet again (4–4 s).
The accounts exceed the node's state cache (4096 per context, `wow.state-cache.capacity`): the steady state is a full cache with evictions.

- `queue-off`: wow.log.append-queue.enabled=false
- `queue-on`: wow.log.append-queue.enabled=true

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| queue-off | 2358.5/s | 512 | 1415.1/s | 60% |
| queue-on | 2223.6/s | 512 | 1415.1/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-queue-off | queue-off | 1886.8/s | 1248.1/s | 24962 | 12774 | 11272.2 ms | 15663.1 ms | 18874.4 ms | n/a | 18939.9 ms | 0.82 cores | kafka 57% · postgres 80% | 0.96 of 1 | load generator 0.96 | 1.75 | 57.2 KB | generator-overload 8940 · exception-ConnectException 3834 |
| probe2-queue-off | queue-off | 1415.1/s | 1415.1/s | 28302 | 0 | 5308.4 ms | 6520.8 ms | 7340.0 ms | n/a | 7831.6 ms | 0.94 cores | kafka 56% · postgres 101% | 0.93 of 1 | postgres 1.01 | 1.78 | 102.1 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-queue-off | queue-off | 1415.1/s | 1376.2/s | 55047 | 1557 | 130.6 ms | 4227.1 ms | 5341.2 ms | n/a | 6455.3 ms | 1.07 cores | kafka 50% · postgres 110% | 0.79 of 1 | postgres 1.10 | 1.94 | 102.8 KB | http-429-wow.command.busy 1557 |
| r1-queue-on | queue-on | 1415.1/s | 1415.1/s | 56604 | 0 | 107.5 ms | 766.0 ms | 1458.2 ms | n/a | 1769.5 ms | 1.06 cores | kafka 52% · postgres 116% | 0.71 of 1 | postgres 1.16 | 1.94 | 104.1 KB | - |
| r2-queue-on | queue-on | 1415.1/s | 1415.1/s | 56604 | 0 | 100.4 ms | 1450.0 ms | 1810.4 ms | n/a | 1900.5 ms | 1.04 cores | kafka 50% · postgres 115% | 0.71 of 1 | postgres 1.15 | 1.90 | 103.2 KB | - |
| r2-queue-off | queue-off | 1415.1/s | 1388.8/s | 55553 | 1051 | 122.4 ms | 3014.7 ms | 4358.1 ms | n/a | 5111.8 ms | 1.06 cores | kafka 51% · postgres 109% | 0.75 of 1 | postgres 1.09 | 1.91 | 99.4 KB | http-429-wow.command.busy 1051 |

Pairs at one rate for both arms, 1415.1/s (latencies compare; lower is better):

| Round | Throughput queue-on/queue-off | p50 queue-on/queue-off | p95 queue-on/queue-off | p99 queue-on/queue-off | p999 queue-on/queue-off | Worst-second p99 queue-on/queue-off | CPU ms/op queue-on/queue-off | Alloc/op queue-on/queue-off |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.028 | 0.824 | 0.181 | 0.273 | - | 0.274 | 0.999 | 1.012 |
| 2 | 1.019 | 0.820 | 0.481 | 0.415 | - | 0.372 | 0.994 | 1.039 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| pg-append-queue@100k | r1-queue-off | steady | **unstable** | second half against first, beyond the default limits: throughput +5.8% (±5.0%), p50 -98.9% (±25.0%) |
| pg-append-queue@100k | r1-queue-off | steady | **error spike** | 1557 of 56604 failed: http-429-wow.command.busy 1557 |
| pg-append-queue@100k | r1-queue-off | steady | **p99 > 10× p50** | p50 130.6 ms, p99 5341.2 ms |
| pg-append-queue@100k | r1-queue-on | steady | **unstable** | second half against first, beyond the default limits: p50 -89.6% (±25.0%) |
| pg-append-queue@100k | r1-queue-on | steady | **p99 > 10× p50** | p50 107.5 ms, p99 1458.2 ms |
| pg-append-queue@100k | r2-queue-on | steady | **unstable** | second half against first, beyond the default limits: p50 -88.3% (±25.0%) |
| pg-append-queue@100k | r2-queue-on | steady | **p99 > 10× p50** | p50 100.4 ms, p99 1810.4 ms |
| pg-append-queue@100k | r2-queue-off | steady | **unstable** | second half against first, beyond the default limits: p50 -98.8% (±25.0%) |
| pg-append-queue@100k | r2-queue-off | steady | **error spike** | 1051 of 56604 failed: http-429-wow.command.busy 1051 |
| pg-append-queue@100k | r2-queue-off | steady | **throughput dip** | 1 s below half the rate (lowest 429/s) |
| pg-append-queue@100k | r2-queue-off | steady | **p99 > 10× p50** | p50 122.4 ms, p99 4358.1 ms |

