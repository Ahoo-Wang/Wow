## m5max-axon-same-rate@100k

The same work for both on the M5 Max: E1 and E3 at half of the lower arm’s peak, W A A W, for their latency and cost per command.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Runner: unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed (7–14 s); every run on it first waited until the nodes were quiet (3–4 s).

Sessions, in order: 1-wow, 1-axon, 2-axon, 2-wow.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |

**Same rate**: for the same work, whose latency and cost are lower. Both arms at one rate, before the sweeps (20 s warm-up, 40 s measured), in session order; kept up: at most 1% failed and at least 97% of the rate completed.

- E1 at 3850/s: no sweep here found a sustainable rate to set it against.
- E3 at 1150/s: no sweep here found a sustainable rate to set it against.

| Scenario | Metric | wow | axon |
| --- | --- | --- | --- |
| E1 | Rate | 3850/s, 3850/s | 3850/s, 3850/s |
| E1 | Throughput | 3850.0/s, 3850.0/s | 3850.0/s, 3657.1/s |
| E1 | Kept up | yes, yes | yes, **no** |
| E1 | Failed | 0, 0 | 0, 7718 |
| E1 | p50 | 1.74 ms, 1.76 ms | 1.14 ms, 1.25 ms |
| E1 | p99 | 4.9 ms, 5.0 ms | 32.6 ms, 909.3 ms |
| E1 | Node CPU ms/op | 0.385, 0.386 | 0.308, 0.306 |
| E1 | PostgreSQL CPU ms/op | 0.185, 0.186 | 0.182, 0.170 |
| E1 | PostgreSQL transactions/op | 1.38, 1.37 | 1.74, 1.52 |
| E1 | PostgreSQL WAL bytes/op | 1.3 KB, 1.3 KB | 665 B, 636 B |
| E1 | Node alloc/op | 90.9 KB, 91.2 KB | 79.0 KB, 74.5 KB |
| E1 | Node GC time | 0.2%, 0.2% | 1.4%, 1.2% |
| E1 | Node RSS max | 1302 MB, 1299 MB | 1580 MB, 1584 MB |
| E3 | Rate | 1150/s, 1150/s | 1150/s, 1150/s |
| E3 | Throughput | 1150.0/s, 1150.0/s | 1150.0/s, 1150.0/s |
| E3 | Kept up | yes, yes | yes, yes |
| E3 | Failed | 0, 0 | 0, 0 |
| E3 | p50 | 3.34 ms, 3.36 ms | 2.85 ms, 2.85 ms |
| E3 | p99 | 7.6 ms, 8.5 ms | 29.7 ms, 37.1 ms |
| E3 | Node CPU ms/op | 1.152, 1.154 | 0.854, 0.855 |
| E3 | PostgreSQL CPU ms/op | 0.577, 0.581 | 0.666, 0.666 |
| E3 | PostgreSQL transactions/op | 4.47, 4.43 | 5.54, 5.57 |
| E3 | PostgreSQL WAL bytes/op | 4.3 KB, 4.3 KB | 3.0 KB, 3.1 KB |
| E3 | Node alloc/op | 225.7 KB, 225.4 KB | 257.4 KB, 257.7 KB |
| E3 | Node GC time | 0.2%, 0.2% | 1.2%, 1.2% |
| E3 | Node RSS max | 1340 MB, 1338 MB | 1622 MB, 1614 MB |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 100000 (100000) | 331001 (331001–331001) | 69001 (69001–69001) | 69001 (69001) | 69001 (69001) | 69001 (69001) | 0 (0) |
| 1-axon | 100000 (100000) | 331001 (331001–331001) | 69001 (69001–69001) | 69001 (69001) | 69001 (69001) | 69001 (69001) | 0 (0) |
| 2-axon | 100000 (100000) | 323283 (323283–323283) | 69001 (69001–69001) | 69001 (69001) | 69001 (69001) | 69001 (69001) | 0 (0) |
| 2-wow | 100000 (100000) | 331001 (331001–331001) | 69001 (69001–69001) | 69001 (69001) | 69001 (69001) | 69001 (69001) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 638004 | 707005 | 426.2 MB | 632 | 100000 | 41.9 MB | 439 | 1.0 MB |
| 1-axon | 707005 | 707005 | 321.5 MB | 477 | 0 | 0.0 MB | - | 0.3 MB |
| 2-axon | 699287 | 699287 | 318.1 MB | 477 | 0 | 0.0 MB | - | 0.3 MB |
| 2-wow | 638004 | 707005 | 425.7 MB | 631 | 100000 | 42.1 MB | 442 | 1.1 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow | 1.8 s | 464.3 MB | 1340.5 MB |
| 1-axon | 1.1 s | 374.1 MB | 1622.1 MB |
| 2-axon | 1.1 s | 388.3 MB | 1614.3 MB |
| 2-wow | 1.8 s | 466.1 MB | 1338.1 MB |

The sweeps' steps (throughput, and p99 or the errors):


| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1 | wow | 3850.0/s | 3850.0/s | 154000 | 0 | 1.7 ms | 2.9 ms | 4.9 ms | n/a | 35.6 ms | 1.48 cores | postgres 71% | 0.57 of 18 | server 1.48 | 0.57 | 90.9 KB | - | - | - |
| 1-wow-e3 | wow | 1150.0/s | 1150.0/s | 46000 | 0 | 3.3 ms | 4.7 ms | 7.6 ms | n/a | 102.4 ms | 1.32 cores | postgres 66% | 0.19 of 18 | server 1.32 | 1.73 | 225.7 KB | - | - | - |
| 1-axon-e1 | axon | 3850.0/s | 3850.0/s | 154000 | 0 | 1.1 ms | 3.3 ms | 32.6 ms | n/a | 184.3 ms | 1.18 cores | postgres 70% | 0.59 of 18 | server 1.18 | 0.49 | 79.0 KB | - | - | - |
| 1-axon-e3 | axon | 1150.0/s | 1150.0/s | 46000 | 0 | 2.8 ms | 4.6 ms | 29.7 ms | n/a | 132.1 ms | 0.98 cores | postgres 77% | 0.18 of 18 | server 0.98 | 1.52 | 257.4 KB | - | - | - |
| 2-axon-e1 | axon | 3850.0/s | 3657.1/s | 146282 | 7718 | 1.3 ms | 741.4 ms | 909.3 ms | n/a | 1179.6 ms | 1.12 cores | postgres 62% | 0.62 of 18 | server 1.12 | 0.48 | 74.5 KB | - | - | generator-overload 7718 |
| 2-axon-e3 | axon | 1150.0/s | 1150.0/s | 46000 | 0 | 2.8 ms | 4.7 ms | 37.1 ms | n/a | 220.2 ms | 0.98 cores | postgres 77% | 0.19 of 18 | server 0.98 | 1.52 | 257.7 KB | - | - | - |
| 2-wow-e1 | wow | 3850.0/s | 3850.0/s | 154000 | 0 | 1.8 ms | 2.9 ms | 5.0 ms | n/a | 17.4 ms | 1.48 cores | postgres 71% | 0.58 of 18 | server 1.48 | 0.57 | 91.2 KB | - | - | - |
| 2-wow-e3 | wow | 1150.0/s | 1150.0/s | 46000 | 0 | 3.4 ms | 4.8 ms | 8.5 ms | n/a | 88.1 ms | 1.33 cores | postgres 67% | 0.18 of 18 | server 1.33 | 1.73 | 225.4 KB | - | - | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| m5max-axon-same-rate@100k | 2-axon-e1 | steady | **unstable** | second half against first, beyond the default limits: throughput +11.1% (±5.0%) |
| m5max-axon-same-rate@100k | 2-axon-e1 | steady | **error spike** | 7718 of 154000 failed: generator-overload 7718; generator-overload: 2000 in flight at most (per-target ceiling) |
| m5max-axon-same-rate@100k | 2-axon-e1 | steady | **p99 > 10× p50** | p50 1.3 ms, p99 909.3 ms |

