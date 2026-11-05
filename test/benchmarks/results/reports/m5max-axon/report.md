# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `main` cc11b16aa893b58f3a450c9ba62eadde16741e45; V10 baseline: none; harness and load generator from local Apple M5 Max.

Tier: ?; coverage: m5max-axon-peak@100k, m5max-axon-same-rate@100k, m5max-axon-pool-32@100k, m5max-axon-contention@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| m5max-axon-peak@100k | 1-wow-e1-own | steady | **unstable** | second half against first, beyond the default limits: p50 +134.1% (±25.0%) |
| m5max-axon-peak@100k | 1-axon-e1-own | steady | **p99 > 10× p50** | p50 1.2 ms, p99 64.3 ms |
| m5max-axon-peak@100k | 2-wow-e1-own | steady | **unstable** | second half against first, beyond the default limits: p50 +75.8% (±25.0%) |
| m5max-axon-peak@100k | 1-wow-e3-own | steady | **error spike** | 2985 of 233092 failed: generator-overload 2985; generator-overload: 2000 in flight at most (per-target ceiling) |
| m5max-axon-peak@100k | 1-axon-e3-own | steady | **unstable** | second half against first, beyond the default limits: throughput +14.6% (±5.0%), p50 -99.6% (±25.0%) |
| m5max-axon-peak@100k | 1-axon-e3-own | steady | **error spike** | 2942 of 46288 failed: generator-overload 2942; generator-overload: 2000 in flight at most (per-target ceiling) |
| m5max-axon-peak@100k | 1-axon-e3-own | steady | **p99 > 10× p50** | p50 4.0 ms, p99 7340.0 ms |
| m5max-axon-peak@100k | 2-wow-e3-own | steady | **error spike** | 3631 of 233364 failed: generator-overload 3631; generator-overload: 2000 in flight at most (per-target ceiling) |
| m5max-axon-same-rate@100k | 2-axon-e1 | steady | **unstable** | second half against first, beyond the default limits: throughput +11.1% (±5.0%) |
| m5max-axon-same-rate@100k | 2-axon-e1 | steady | **error spike** | 7718 of 154000 failed: generator-overload 7718; generator-overload: 2000 in flight at most (per-target ceiling) |
| m5max-axon-same-rate@100k | 2-axon-e1 | steady | **p99 > 10× p50** | p50 1.3 ms, p99 909.3 ms |
| m5max-axon-contention@100k | 1-wow (E3 zipf) | store | **store does not match the runs** | transfers entered or refused: 552570 stored, 555013 expected |
| m5max-axon-contention@100k | 1-wow (E3 zipf) | store | **store does not match the runs** | entries confirmed: 548931 stored, 552570 expected |

## The industry comparison

What each server does for a command:

| | Wow | Axon |
| --- | --- | --- |
| Event store | Its PostgreSQL log: a row per command, its events in one jsonb array, 64 partitions | The JDBC event storage engine: a row per event (domainevententry), the payload JSON in bytea |
| Unit of work | A command's events committed with one append; an aggregate's commands queued behind it committed with it (up to 32), and appends of several partitions merged into one statement | A unit of work and a Spring transaction per command, an INSERT per event; the transfer handler's command joins the processor's unit of work |
| Idempotency | Each command stored with its request id under a unique key (tenant, request id), a new id per request here | None: a command sent again is handled again |
| Aggregate cache | The state cache: strong references, a tenth of the heap in estimated bytes (about 105,000 accounts at 1 GB) | BoundedCache: strong references, 131,072 aggregates, the first put out first |
| Snapshots | The built-in snapshot subscription keeps every account's latest state, on its partition's owner, after the commit | Every 100 events of an account, on two snapshotter threads, after the commit |
| Concurrency of commands | Coroutines: one lane per aggregate in arrival order, a bound on the commands waiting in a lane and on the node's commands in flight (429 past them) | The command bus handles a command on its sender's thread, one of 200 platform threads (Tomcat's default), an aggregate locked while it is handled; past them commands queue, no bound |
| Transfer (E3) | TransferSaga, the built-in subscription of the account's state events, on the partition owners, from the log's tail | An event handler in a tracking processor of 16 segments and threads, sized by its reactions' PostgreSQL round trips, not by the CPUs (axon-pg-segments compares 2), its tokens in PostgreSQL; a reaction that fails is retried (PropagatingErrorHandler) |
| Answer | The wait of the request (`Wow-Wait`): E1 `committed`, E3 the target's entry committed; 504 after 10 s | The command's result: E1 once its unit of work committed, E3 once the target's entry committed with the processor's unit of work; 504 after 10 s |
| Serialization | Jackson, events and snapshots as JSON (jsonb) | Jackson for every message and snapshot (JSON in bytea) |
| Connections | HikariCP at Spring Boot's default, 10 | HikariCP at Spring Boot's default, 10 |
| Metrics and tracing | None: wow-observability is not on the server's classpath (D174) | None: neither axon-micrometer nor Axon's tracing modules are on its classpath, so its monitors and spans are no-ops |
| Bus | None (wow.transport=none) | None (no Axon Server) |
| HTTP | Spring Boot 4.1.2, WebFlux on Reactor Netty | Spring Boot 3.5.16, WebFlux on Reactor Netty (Axon Framework 4 runs on Spring Boot 3): other Spring, Reactor and Netty versions |

### m5max-axon-peak@100k

The industry comparison on the M5 Max: Wow with no bus (wow.transport=none) against Axon Framework 4 on one PostgreSQL, one node each, W A A W: closed-loop sweeps of E1 and E3 to their peaks, then each at half of its own sustainable rate.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37311/s at 2048; 37344/s at 2048 | 7725/s at 256; 7716/s at 256 | 4.83–4.84 | 0.1% | wow higher |
| E3 | 12404/s at 2048; 12042/s at 2048 | 2314/s at 64; 2326/s at 128 | 5.18–5.36 | 3.0% | wow higher |

The load generator used at most 3.10 of its 18 CPUs at a peak, **more than 1.6**; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37311/s at 2048 (p99 99.3 ms); 37344/s at 2048 (p99 105.5 ms) | 7725/s at 256 (p99 126.5 ms); 7716/s at 256 (p99 126.5 ms) | 4.83–4.84 | 0.1% | wow higher |
| E3 | 11655/s at 1024 (p99 149.5 ms); 11668/s at 1024 (p99 140.3 ms) | 2314/s at 64 (p99 100.4 ms); 2320/s at 32 (p99 38.9 ms) | 5.02–5.04 | 0.2% | wow higher |

**Own share**: with the same headroom, each one's latency and cost per command. Each arm at 50% of the sustainable rate its own sweep found in the session, after the sweeps, in session order.

| Scenario | Metric | wow | axon |
| --- | --- | --- | --- |
| E1 | Rate | 18656/s, 18672/s | 3862/s, 3858/s |
| E1 | Throughput | 18558.5/s, 18599.8/s | 3862.5/s, 3858.1/s |
| E1 | Kept up | yes, yes | yes, yes |
| E1 | Failed | 3887, 2881 | 0, 0 |
| E1 | p50 | 16.06 ms, 16.51 ms | 1.18 ms, 1.22 ms |
| E1 | p99 | 77.3 ms, 68.6 ms | 64.3 ms, 9.3 ms |
| E1 | Node CPU ms/op | 0.207, 0.206 | 0.286, 0.301 |
| E1 | PostgreSQL CPU ms/op | 0.096, 0.096 | 0.188, 0.188 |
| E1 | PostgreSQL transactions/op | 0.15, 0.16 | 1.75, 1.74 |
| E1 | PostgreSQL WAL bytes/op | 1.1 KB, 1.1 KB | 691 B, 693 B |
| E1 | Node alloc/op | 75.7 KB, 75.7 KB | 79.0 KB, 78.9 KB |
| E1 | Node GC time | 1.1%, 1.1% | 0.4%, 0.5% |
| E1 | Node RSS max | 1508 MB, 1497 MB | 1586 MB, 1576 MB |
| E3 | Rate | 5827/s, 5834/s | 1157/s, 1160/s |
| E3 | Throughput | 5752.7/s, 5743.3/s | 1083.7/s, 1159.8/s |
| E3 | Kept up | **no**, **no** | **no**, yes |
| E3 | Failed | 2985, 3631 | 2942, 0 |
| E3 | p50 | 69.12 ms, 68.61 ms | 3.95 ms, 2.99 ms |
| E3 | p99 | 454.7 ms, 434.2 ms | 7340.0 ms, 38.7 ms |
| E3 | Node CPU ms/op | 0.392, 0.386 | 0.861, 0.927 |
| E3 | PostgreSQL CPU ms/op | 0.254, 0.251 | 0.623, 0.681 |
| E3 | PostgreSQL transactions/op | 0.28, 0.27 | 4.48, 5.44 |
| E3 | PostgreSQL WAL bytes/op | 3.8 KB, 3.8 KB | 3.0 KB, 3.1 KB |
| E3 | Node alloc/op | 182.8 KB, 183.7 KB | 279.5 KB, 257.6 KB |
| E3 | Node GC time | 0.7%, 0.7% | 0.7%, 1.4% |
| E3 | Node RSS max | 1498 MB, 1495 MB | 1605 MB, 1610 MB |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow (E1) | 100000 (100000) | 6322648 (6322608–6325024) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon (E1) | 100000 (100000) | 1770571 (1770240–1771147) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon (E1) | 100000 (100000) | 1773704 (1773623–1774073) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow (E1) | 100000 (100000) | 6320876 (6320876–6322338) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-wow (E3) | 100000 (100000) | 100001 (100001–100001) | 2082981 (2080150–2337854) | 2082981 (2082981) | 2082981 (2082981) | 2082981 (2082981) | 0 (0) |
| 1-axon (E3) | 100000 (100000) | 100001 (100001–100001) | 623940 (623813–623940) | 623940 (623940) | 623940 (623940) | 623940 (623940) | 0 (0) |
| 2-axon (E3) | 100000 (100000) | 100001 (100001–100001) | 633495 (633495–634175) | 633495 (633495) | 633495 (633495) | 633495 (633495) | 0 (0) |
| 2-wow (E3) | 100000 (100000) | 100001 (100001–100001) | 2072084 (2068207–2385419) | 2072084 (2072084) | 2072084 (2072084) | 2072084 (2072084) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow (E1) | 6422651 | 6422652 | 3764.5 MB | 615 | 100000 | 47.8 MB | 502 | 1.3 MB |
| 1-axon (E1) | 1870575 | 1870575 | 824.0 MB | 462 | 0 | 0.0 MB | - | 0.1 MB |
| 2-axon (E1) | 1873708 | 1873708 | 826.0 MB | 462 | 0 | 0.0 MB | - | 0.1 MB |
| 2-wow (E1) | 6420879 | 6420880 | 3763.6 MB | 615 | 100000 | 48.0 MB | 503 | 1.6 MB |
| 1-wow (E3) | 6448944 | 8531925 | 4879.4 MB | 600 | 100000 | 52.8 MB | 553 | 1.0 MB |
| 1-axon (E3) | 2695761 | 2695761 | 1285.0 MB | 500 | 46 | 0.1 MB | 2671 | 0.4 MB |
| 2-axon (E3) | 2733981 | 2733981 | 1304.4 MB | 500 | 51 | 0.1 MB | 2249 | 0.5 MB |
| 2-wow (E3) | 6416253 | 8488337 | 4855.1 MB | 600 | 100000 | 51.5 MB | 540 | 1.0 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow (E1) | 1.8 s | 460.6 MB | 1508.1 MB |
| 1-axon (E1) | 1.1 s | 370.6 MB | 1586.3 MB |
| 2-axon (E1) | 1.2 s | 384.6 MB | 1575.7 MB |
| 2-wow (E1) | 1.8 s | 487.4 MB | 1496.6 MB |
| 1-wow (E3) | 1.8 s | 486.4 MB | 1497.8 MB |
| 1-axon (E3) | 1.1 s | 366.9 MB | 1605.0 MB |
| 2-axon (E3) | 1.1 s | 396.9 MB | 1610.0 MB |
| 2-wow (E3) | 1.8 s | 484.6 MB | 1495.2 MB |

### m5max-axon-same-rate@100k

The same work for both on the M5 Max: E1 and E3 at half of the lower arm’s peak, W A A W, for their latency and cost per command.

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

### m5max-axon-pool-32@100k

Both pools at 32 connections instead of Spring Boot’s 10, on the M5 Max: E1 and E3 to their peaks, W A A W (Axon’s 16 processor threads share 10 connections by default).

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow-pool-32/axon-pool-32 from the lowest wow-pool-32 peak over the highest axon-pool-32 peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow-pool-32 | axon-pool-32 | wow-pool-32/axon-pool-32 | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37065/s at 2048; 37554/s at 2048 | 14538/s at 512; 14771/s at 512 | 2.51–2.58 | 1.6% | wow-pool-32 higher |
| E3 | 12386/s at 2048; 12510/s at 2048 | 3742/s at 128; 3718/s at 256 | 3.31–3.36 | 1.0% | wow-pool-32 higher |

The load generator used at most 3.09 of its 18 CPUs at a peak, **more than 1.6**; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow-pool-32 | axon-pool-32 | wow-pool-32/axon-pool-32 | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37065/s at 2048 (p99 108.5 ms); 37554/s at 2048 (p99 94.7 ms) | 14538/s at 512 (p99 135.2 ms); 14771/s at 512 (p99 128.5 ms) | 2.51–2.58 | 1.6% | wow-pool-32 higher |
| E3 | 11572/s at 1024 (p99 143.4 ms); 11713/s at 1024 (p99 136.2 ms) | 3684/s at 64 (p99 68.1 ms); 3698/s at 64 (p99 65.3 ms) | 3.13–3.18 | 1.2% | wow-pool-32 higher |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32 (E1) | 100000 (100000) | 5158399 (5157732–5161269) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon-pool-32 (E1) | 100000 (100000) | 3260892 (3260594–3260918) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon-pool-32 (E1) | 100000 (100000) | 2665003 (2664930–2665099) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow-pool-32 (E1) | 100000 (100000) | 5153771 (5153236–5158428) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-wow-pool-32 (E3) | 100000 (100000) | 100001 (100001–100001) | 1729798 (1725281–1893924) | 1729798 (1729798) | 1729798 (1729798) | 1729798 (1729798) | 0 (0) |
| 1-axon-pool-32 (E3) | 100000 (100000) | 100001 (100001–100001) | 812455 (812455–812491) | 812455 (812455) | 812455 (812455) | 812455 (812455) | 0 (0) |
| 2-axon-pool-32 (E3) | 100000 (100000) | 100001 (100001–100001) | 994285 (994272–994312) | 994285 (994285) | 994285 (994285) | 994285 (994285) | 0 (0) |
| 2-wow-pool-32 (E3) | 100000 (100000) | 100001 (100001–100001) | 1739683 (1735536–1927894) | 1739683 (1739683) | 1739683 (1739683) | 1739683 (1739683) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32 (E1) | 5258402 | 5258403 | 3072.0 MB | 613 | 100000 | 46.9 MB | 492 | 1.1 MB |
| 1-axon-pool-32 (E1) | 3360896 | 3360896 | 1465.5 MB | 457 | 0 | 0.0 MB | - | 0.1 MB |
| 2-axon-pool-32 (E1) | 2765007 | 2765007 | 1196.9 MB | 454 | 0 | 0.0 MB | - | 0.1 MB |
| 2-wow-pool-32 (E1) | 5253774 | 5253775 | 3071.8 MB | 613 | 100000 | 47.1 MB | 494 | 1.2 MB |
| 1-wow-pool-32 (E3) | 5389395 | 7119193 | 4065.1 MB | 599 | 100000 | 49.5 MB | 519 | 1.1 MB |
| 1-axon-pool-32 (E3) | 3449821 | 3449821 | 1655.9 MB | 503 | 258 | 0.2 MB | 889 | 1.6 MB |
| 2-axon-pool-32 (E3) | 4177141 | 4177141 | 2009.8 MB | 505 | 1001 | 0.5 MB | 540 | 1.6 MB |
| 2-wow-pool-32 (E3) | 5419050 | 7158733 | 4088.3 MB | 599 | 100000 | 43.9 MB | 460 | 1.4 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow-pool-32 (E1) | 1.8 s | 469.6 MB | 1483.9 MB |
| 1-axon-pool-32 (E1) | 1.1 s | 376.3 MB | 1634.5 MB |
| 2-axon-pool-32 (E1) | 1.1 s | 400.4 MB | 1647.0 MB |
| 2-wow-pool-32 (E1) | 1.8 s | 469.8 MB | 1490.6 MB |
| 1-wow-pool-32 (E3) | 1.8 s | 513.4 MB | 1491.6 MB |
| 1-axon-pool-32 (E3) | 1.2 s | 366.6 MB | 1590.8 MB |
| 2-axon-pool-32 (E3) | 1.1 s | 361.2 MB | 1597.4 MB |
| 2-wow-pool-32 (E3) | 1.8 s | 500.4 MB | 1494.6 MB |

### m5max-axon-contention@100k

Contention on the M5 Max: E1 and E3 with Zipf keys and E1 on hot keys to their peaks, Wow with no bus against Axon Framework 4, W A A W.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 zipf | 23812/s at 2048; ≥ 23352/s at 2048 (top step) | 6142/s at 512; 6220/s at 256 | ≥ 3.75–3.88 | 2.0% | not comparable (wow: the store check) |
| E3 zipf | 2447/s at 64; 2562/s at 32 | 1785/s at 32; 1769/s at 64 | 1.37–1.45 | 4.7% | not comparable (wow: the store check) |
| E1 hot | ≥ 23943/s at 2048 (top step); ≥ 23634/s at 2048 (top step) | 2654/s at 256; 2633/s at 256 | ≥ 8.90–9.09 | 1.3% | not comparable (wow: the store check) |

The load generator used at most 3.77 of its 18 CPUs at a peak, **more than 1.6**; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 zipf | 19837/s at 512 (p99 135.2 ms); 18781/s at 512 (p99 144.4 ms) | 6142/s at 512 (p99 182.3 ms); 6220/s at 256 (p99 132.1 ms) | 3.02–3.23 | 5.6% | not comparable (wow: the store check) |
| E3 zipf | 2447/s at 64 (p99 169.0 ms); 2562/s at 32 (p99 65.0 ms) | 1785/s at 32 (p99 90.1 ms); 1769/s at 64 (p99 189.4 ms) | 1.37–1.45 | 4.7% | not comparable (wow: the store check) |
| E1 hot | 14274/s at 128 (p99 19.5 ms); 13618/s at 256 (p99 49.7 ms) | 2654/s at 256 (p99 177.2 ms); 2633/s at 256 (p99 178.2 ms) | 5.13–5.42 | 4.8% | not comparable (wow: the store check) |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit). **Not comparable: wow.**

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow (E1 zipf) | 100000 (100000) | 4398198 (4398017–4466207) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon (E1 zipf) | 100000 (100000) | 1275537 (1275005–1275595) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon (E1 zipf) | 100000 (100000) | 1579035 (1578758–1579279) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow (E1 zipf) | 100000 (100000) | 4095741 (4095741–4122972) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-wow (E3 zipf) | 100000 (100000) | 100001 (100001–100001) | 555013 (547513–616749) | 555013 (555013) | **552570 (555013)** | **548931 (552570)** | 0 (0) |
| 1-axon (E3 zipf) | 100000 (100000) | 100001 (100001–100001) | 568898 (567186–569365) | 568898 (568898) | 568898 (568898) | 568898 (568898) | 0 (0) |
| 2-axon (E3 zipf) | 100000 (100000) | 100001 (100001–100001) | 492397 (491299–494074) | 492397 (492397) | 492397 (492397) | 492397 (492397) | 0 (0) |
| 2-wow (E3 zipf) | 100000 (100000) | 100001 (100001–100001) | 740979 (728367–796440) | 740979 (740979) | 740979 (740979) | 740979 (740979) | 0 (0) |
| 1-wow (E1 hot) | 100000 (100000) | 4120612 (4120548–5134824) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon (E1 hot) | 100000 (100000) | 649967 (649787–650019) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon (E1 hot) | 100000 (100000) | 661493 (661320–661538) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow (E1 hot) | 100000 (100000) | 3863025 (3862965–4911069) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow (E1 zipf) | 4498201 | 4498202 | 2692.4 MB | 628 | 100000 | 41.8 MB | 438 | 1.4 MB |
| 1-axon (E1 zipf) | 1375541 | 1375541 | 617.5 MB | 471 | 820 | 0.9 MB | 1129 | 0.1 MB |
| 2-axon (E1 zipf) | 1679039 | 1679039 | 753.2 MB | 470 | 1018 | 0.9 MB | 958 | 0.1 MB |
| 2-wow (E1 zipf) | 4195744 | 4195745 | 2513.4 MB | 628 | 100000 | 41.9 MB | 439 | 1.4 MB |
| 1-wow (E3 zipf) | 1856515 | 2411528 | 1415.7 MB | 616 | 100000 | 41.7 MB | 438 | 1.6 MB |
| 1-axon (E3 zipf) | 2475593 | 2475593 | 1212.3 MB | 513 | 1607 | 1.2 MB | 785 | 0.6 MB |
| 2-axon (E3 zipf) | 2169589 | 2169589 | 1061.9 MB | 513 | 1404 | 1.2 MB | 869 | 0.6 MB |
| 2-wow (E3 zipf) | 2422938 | 3163917 | 1853.0 MB | 614 | 100000 | 41.9 MB | 439 | 1.6 MB |
| 1-wow (E1 hot) | 4220615 | 4220616 | 2495.6 MB | 620 | 100000 | 41.9 MB | 440 | 1.2 MB |
| 1-axon (E1 hot) | 749971 | 749971 | 338.5 MB | 473 | 1 | 0.3 MB | 278528 | 0.0 MB |
| 2-axon (E1 hot) | 761497 | 761497 | 343.8 MB | 473 | 1 | 0.3 MB | 278528 | 0.1 MB |
| 2-wow (E1 hot) | 3963028 | 3963029 | 2342.8 MB | 620 | 100000 | 42.0 MB | 440 | 1.3 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow (E1 zipf) | 1.8 s | 464.7 MB | 1494.6 MB |
| 1-axon (E1 zipf) | 1.1 s | 378.9 MB | 1616.2 MB |
| 2-axon (E1 zipf) | 1.1 s | 378.9 MB | 1610.2 MB |
| 2-wow (E1 zipf) | 1.8 s | 471.1 MB | 1475.4 MB |
| 1-wow (E3 zipf) | 1.8 s | 462.5 MB | 1527.1 MB |
| 1-axon (E3 zipf) | 1.1 s | 403.2 MB | 1593.2 MB |
| 2-axon (E3 zipf) | 1.1 s | 382.0 MB | 1605.4 MB |
| 2-wow (E3 zipf) | 1.8 s | 475.7 MB | 1509.8 MB |
| 1-wow (E1 hot) | 1.8 s | 465.4 MB | 1462.4 MB |
| 1-axon (E1 hot) | 1.2 s | 371.2 MB | 1585.0 MB |
| 2-axon (E1 hot) | 1.1 s | 397.6 MB | 1610.1 MB |
| 2-wow (E1 hot) | 1.8 s | 470.4 MB | 1483.6 MB |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| m5max-axon-peak@100k E1 | unknown | ? | ? GB | Eclipse Adoptium 25.0.5+8-LTS | unpinned |
| m5max-axon-peak@100k E3 | unknown | ? | ? GB | Eclipse Adoptium 25.0.5+8-LTS | unpinned |
| m5max-axon-same-rate@100k | unknown | ? | ? GB | Eclipse Adoptium 25.0.5+8-LTS | unpinned |
| m5max-axon-pool-32@100k E1 | unknown | ? | ? GB | Eclipse Adoptium 25.0.5+8-LTS | unpinned |
| m5max-axon-pool-32@100k E3 | unknown | ? | ? GB | Eclipse Adoptium 25.0.5+8-LTS | unpinned |
| m5max-axon-contention@100k E1 zipf | unknown | ? | ? GB | Eclipse Adoptium 25.0.5+8-LTS | unpinned |
| m5max-axon-contention@100k E3 zipf | unknown | ? | ? GB | Eclipse Adoptium 25.0.5+8-LTS | unpinned |
| m5max-axon-contention@100k E1 hot | unknown | ? | ? GB | Eclipse Adoptium 25.0.5+8-LTS | unpinned |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |

---

### m5max-axon-peak@100k

The industry comparison on the M5 Max: Wow with no bus (wow.transport=none) against Axon Framework 4 on one PostgreSQL, one node each, W A A W: closed-loop sweeps of E1 and E3 to their peaks, then each at half of its own sustainable rate.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Each sweep in fresh sessions on a runner of its own, the full ABBA: E1 on unknown, ? cores; E3 on unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed (7–14 s); every run on it first waited until the nodes were quiet (3–7 s).

Sessions, in order: 1-wow (E1), 1-axon (E1), 2-axon (E1), 2-wow (E1), 1-wow (E3), 1-axon (E3), 2-axon (E3), 2-wow (E3).

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37311/s at 2048; 37344/s at 2048 | 7725/s at 256; 7716/s at 256 | 4.83–4.84 | 0.1% | wow higher |
| E3 | 12404/s at 2048; 12042/s at 2048 | 2314/s at 64; 2326/s at 128 | 5.18–5.36 | 3.0% | wow higher |

The load generator used at most 3.10 of its 18 CPUs at a peak, **more than 1.6**; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37311/s at 2048 (p99 99.3 ms); 37344/s at 2048 (p99 105.5 ms) | 7725/s at 256 (p99 126.5 ms); 7716/s at 256 (p99 126.5 ms) | 4.83–4.84 | 0.1% | wow higher |
| E3 | 11655/s at 1024 (p99 149.5 ms); 11668/s at 1024 (p99 140.3 ms) | 2314/s at 64 (p99 100.4 ms); 2320/s at 32 (p99 38.9 ms) | 5.02–5.04 | 0.2% | wow higher |

**Own share**: with the same headroom, each one's latency and cost per command. Each arm at 50% of the sustainable rate its own sweep found in the session, after the sweeps, in session order.

| Scenario | Metric | wow | axon |
| --- | --- | --- | --- |
| E1 | Rate | 18656/s, 18672/s | 3862/s, 3858/s |
| E1 | Throughput | 18558.5/s, 18599.8/s | 3862.5/s, 3858.1/s |
| E1 | Kept up | yes, yes | yes, yes |
| E1 | Failed | 3887, 2881 | 0, 0 |
| E1 | p50 | 16.06 ms, 16.51 ms | 1.18 ms, 1.22 ms |
| E1 | p99 | 77.3 ms, 68.6 ms | 64.3 ms, 9.3 ms |
| E1 | Node CPU ms/op | 0.207, 0.206 | 0.286, 0.301 |
| E1 | PostgreSQL CPU ms/op | 0.096, 0.096 | 0.188, 0.188 |
| E1 | PostgreSQL transactions/op | 0.15, 0.16 | 1.75, 1.74 |
| E1 | PostgreSQL WAL bytes/op | 1.1 KB, 1.1 KB | 691 B, 693 B |
| E1 | Node alloc/op | 75.7 KB, 75.7 KB | 79.0 KB, 78.9 KB |
| E1 | Node GC time | 1.1%, 1.1% | 0.4%, 0.5% |
| E1 | Node RSS max | 1508 MB, 1497 MB | 1586 MB, 1576 MB |
| E3 | Rate | 5827/s, 5834/s | 1157/s, 1160/s |
| E3 | Throughput | 5752.7/s, 5743.3/s | 1083.7/s, 1159.8/s |
| E3 | Kept up | **no**, **no** | **no**, yes |
| E3 | Failed | 2985, 3631 | 2942, 0 |
| E3 | p50 | 69.12 ms, 68.61 ms | 3.95 ms, 2.99 ms |
| E3 | p99 | 454.7 ms, 434.2 ms | 7340.0 ms, 38.7 ms |
| E3 | Node CPU ms/op | 0.392, 0.386 | 0.861, 0.927 |
| E3 | PostgreSQL CPU ms/op | 0.254, 0.251 | 0.623, 0.681 |
| E3 | PostgreSQL transactions/op | 0.28, 0.27 | 4.48, 5.44 |
| E3 | PostgreSQL WAL bytes/op | 3.8 KB, 3.8 KB | 3.0 KB, 3.1 KB |
| E3 | Node alloc/op | 182.8 KB, 183.7 KB | 279.5 KB, 257.6 KB |
| E3 | Node GC time | 0.7%, 0.7% | 0.7%, 1.4% |
| E3 | Node RSS max | 1498 MB, 1495 MB | 1605 MB, 1610 MB |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow (E1) | 100000 (100000) | 6322648 (6322608–6325024) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon (E1) | 100000 (100000) | 1770571 (1770240–1771147) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon (E1) | 100000 (100000) | 1773704 (1773623–1774073) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow (E1) | 100000 (100000) | 6320876 (6320876–6322338) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-wow (E3) | 100000 (100000) | 100001 (100001–100001) | 2082981 (2080150–2337854) | 2082981 (2082981) | 2082981 (2082981) | 2082981 (2082981) | 0 (0) |
| 1-axon (E3) | 100000 (100000) | 100001 (100001–100001) | 623940 (623813–623940) | 623940 (623940) | 623940 (623940) | 623940 (623940) | 0 (0) |
| 2-axon (E3) | 100000 (100000) | 100001 (100001–100001) | 633495 (633495–634175) | 633495 (633495) | 633495 (633495) | 633495 (633495) | 0 (0) |
| 2-wow (E3) | 100000 (100000) | 100001 (100001–100001) | 2072084 (2068207–2385419) | 2072084 (2072084) | 2072084 (2072084) | 2072084 (2072084) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow (E1) | 6422651 | 6422652 | 3764.5 MB | 615 | 100000 | 47.8 MB | 502 | 1.3 MB |
| 1-axon (E1) | 1870575 | 1870575 | 824.0 MB | 462 | 0 | 0.0 MB | - | 0.1 MB |
| 2-axon (E1) | 1873708 | 1873708 | 826.0 MB | 462 | 0 | 0.0 MB | - | 0.1 MB |
| 2-wow (E1) | 6420879 | 6420880 | 3763.6 MB | 615 | 100000 | 48.0 MB | 503 | 1.6 MB |
| 1-wow (E3) | 6448944 | 8531925 | 4879.4 MB | 600 | 100000 | 52.8 MB | 553 | 1.0 MB |
| 1-axon (E3) | 2695761 | 2695761 | 1285.0 MB | 500 | 46 | 0.1 MB | 2671 | 0.4 MB |
| 2-axon (E3) | 2733981 | 2733981 | 1304.4 MB | 500 | 51 | 0.1 MB | 2249 | 0.5 MB |
| 2-wow (E3) | 6416253 | 8488337 | 4855.1 MB | 600 | 100000 | 51.5 MB | 540 | 1.0 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow (E1) | 1.8 s | 460.6 MB | 1508.1 MB |
| 1-axon (E1) | 1.1 s | 370.6 MB | 1586.3 MB |
| 2-axon (E1) | 1.2 s | 384.6 MB | 1575.7 MB |
| 2-wow (E1) | 1.8 s | 487.4 MB | 1496.6 MB |
| 1-wow (E3) | 1.8 s | 486.4 MB | 1497.8 MB |
| 1-axon (E3) | 1.1 s | 366.9 MB | 1605.0 MB |
| 2-axon (E3) | 1.1 s | 396.9 MB | 1610.0 MB |
| 2-wow (E3) | 1.8 s | 484.6 MB | 1495.2 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-sweep | wow | 37311/s at 2048 | peaked below its top step | - | - | - | - | - | - | 37311/s, p99 99.3 ms | 36073/s, p99 250.9 ms |
| 1-axon-e1-sweep | axon | 7725/s at 256 | peaked below its top step | - | - | - | 7725/s, p99 126.5 ms | 7640/s, p99 161.8 ms | - | - | - |
| 2-axon-e1-sweep | axon | 7716/s at 256 | peaked below its top step | - | - | - | 7716/s, p99 126.5 ms | 5538/s, p99 378.9 ms | - | - | - |
| 2-wow-e1-sweep | wow | 37344/s at 2048 | peaked below its top step | - | - | - | - | - | - | 37344/s, p99 105.5 ms | 36320/s, p99 200.7 ms |
| 1-wow-e3-sweep | wow | 12404/s at 2048 | more than 1% failed | - | - | - | - | - | 11655/s, p99 149.5 ms | 12404/s, p99 276.5 ms | - |
| 1-axon-e3-sweep | axon | 2314/s at 64 | peaked below its top step | 2311/s, p99 42.0 ms | 2314/s, p99 100.4 ms | 2195/s, p99 356.4 ms | - | - | - | - | - |
| 2-axon-e3-sweep | axon | 2326/s at 128 | peaked below its top step | 2320/s, p99 38.9 ms | 2311/s, p99 95.2 ms | 2326/s, p99 389.1 ms | - | - | - | - | - |
| 2-wow-e3-sweep | wow | 12042/s at 2048 | more than 1% failed | - | - | - | - | - | 11668/s, p99 140.3 ms | 12042/s, p99 667.6 ms | - |

The short steps that located them (3 s warm-up, 8 s measured), not part of the results:

| Sweep | Arm | 1 clients | 2 clients | 4 clients | 8 clients | 16 clients | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-sweep | wow | 1475/s, p99 1.0 ms | 2159/s, p99 1.2 ms | 2678/s, p99 2.5 ms | 4062/s, p99 5.5 ms | 6636/s, p99 4.8 ms | 9970/s, p99 6.5 ms | 14168/s, p99 9.0 ms | 18662/s, p99 13.6 ms | 18701/s, p99 24.3 ms | 18026/s, p99 43.8 ms | 24969/s, p99 67.6 ms | 38973/s, p99 84.0 ms | 36739/s, p99 214.0 ms |
| 1-axon-e1-sweep | axon | 1390/s, p99 1.2 ms | 2103/s, p99 2.1 ms | 3720/s, p99 1.7 ms | 5295/s, p99 4.3 ms | 5191/s, p99 10.3 ms | 7384/s, p99 7.6 ms | 7477/s, p99 25.1 ms | 7430/s, p99 47.4 ms | 7916/s, p99 115.2 ms | 7688/s, p99 156.7 ms | 7766/s, p99 222.2 ms | 7705/s, p99 358.4 ms | 7585/s, p99 634.9 ms |
| 2-axon-e1-sweep | axon | 1383/s, p99 1.1 ms | 1672/s, p99 4.5 ms | 3676/s, p99 1.7 ms | 5850/s, p99 2.6 ms | 6997/s, p99 5.2 ms | 7542/s, p99 7.3 ms | 7558/s, p99 20.7 ms | 7470/s, p99 45.6 ms | 7886/s, p99 123.9 ms | 7785/s, p99 155.6 ms | 7697/s, p99 222.2 ms | 7680/s, p99 360.4 ms | 7551/s, p99 634.9 ms |
| 2-wow-e1-sweep | wow | 707/s, p99 3.9 ms | 2129/s, p99 1.2 ms | 2676/s, p99 2.5 ms | 4041/s, p99 4.5 ms | 6516/s, p99 4.9 ms | 9844/s, p99 6.4 ms | 13918/s, p99 9.2 ms | 18424/s, p99 14.5 ms | 17744/s, p99 25.9 ms | 17793/s, p99 43.0 ms | 26868/s, p99 64.5 ms | 38787/s, p99 81.4 ms | 36613/s, p99 247.8 ms |
| 1-wow-e3-sweep | wow | 348/s, p99 7.7 ms | 214/s, p99 21.8 ms | 1053/s, p99 11.0 ms | 1816/s, p99 7.6 ms | 2909/s, p99 9.7 ms | 4166/s, p99 13.6 ms | 5486/s, p99 19.6 ms | 6848/s, p99 32.8 ms | 5605/s, p99 66.6 ms | 7851/s, p99 98.3 ms | 12632/s, p99 153.6 ms | 14031/s, p99 272.4 ms | 6766/s, 108248 errors |
| 1-axon-e3-sweep | axon | 520/s, p99 2.7 ms | 801/s, p99 5.9 ms | 1339/s, p99 4.8 ms | 2043/s, p99 7.5 ms | 2236/s, p99 27.5 ms | 2362/s, p99 36.9 ms | 2395/s, p99 104.4 ms | 2037/s, p99 342.0 ms | 2369/s, p99 536.6 ms | 2371/s, p99 725.0 ms | 2309/s, p99 1073.2 ms | 2299/s, p99 3506.2 ms | 1971/s, p99 2867.2 ms |
| 2-axon-e3-sweep | axon | 514/s, p99 2.7 ms | 785/s, p99 5.2 ms | 1347/s, p99 4.8 ms | 2038/s, p99 7.4 ms | 2284/s, p99 17.2 ms | 2353/s, p99 35.3 ms | 2356/s, p99 109.1 ms | 2321/s, p99 208.9 ms | 2353/s, p99 569.3 ms | 2349/s, p99 761.9 ms | 2334/s, p99 1548.3 ms | 2307/s, p99 3506.2 ms | 1896/s, p99 3194.9 ms |
| 2-wow-e3-sweep | wow | 604/s, p99 2.6 ms | 842/s, p99 3.5 ms | 1184/s, p99 5.1 ms | 1778/s, p99 9.3 ms | 2882/s, p99 9.6 ms | 4171/s, p99 13.2 ms | 5524/s, p99 20.0 ms | 6258/s, p99 35.8 ms | 5736/s, p99 59.4 ms | 7832/s, p99 114.7 ms | 12481/s, p99 161.8 ms | 14204/s, p99 217.1 ms | 4726/s, 163459 errors |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-own | wow | 18655.7/s | 18558.5/s | 742341 | 3887 | 16.1 ms | 54.5 ms | 77.3 ms | n/a | 234.5 ms | 3.83 cores | postgres 179% | 2.75 of 18 | server 3.83 | 0.30 | 75.7 KB | - | - | generator-overload 3887 |
| 1-axon-e1-own | axon | 3862.5/s | 3862.5/s | 154500 | 0 | 1.2 ms | 2.1 ms | 64.3 ms | n/a | 297.0 ms | 1.11 cores | postgres 73% | 0.61 of 18 | server 1.11 | 0.47 | 79.0 KB | - | - | - |
| 2-axon-e1-own | axon | 3858.1/s | 3858.1/s | 154324 | 0 | 1.2 ms | 1.8 ms | 9.3 ms | n/a | 77.8 ms | 1.16 cores | postgres 73% | 0.67 of 18 | server 1.16 | 0.49 | 78.9 KB | - | - | - |
| 2-wow-e1-own | wow | 18671.9/s | 18599.8/s | 743994 | 2881 | 16.5 ms | 54.5 ms | 68.6 ms | n/a | 280.6 ms | 3.84 cores | postgres 178% | 2.74 of 18 | server 3.84 | 0.30 | 75.7 KB | - | - | generator-overload 2881 |
| 1-wow-e3-own | wow | 5827.3/s | 5752.7/s | 230107 | 2985 | 69.1 ms | 155.6 ms | 454.7 ms | n/a | 569.3 ms | 2.26 cores | postgres 146% | 1.03 of 18 | server 2.26 | 0.65 | 182.8 KB | - | - | generator-overload 2985 |
| 1-axon-e3-own | axon | 1157.2/s | 1083.7/s | 43346 | 2942 | 4.0 ms | 5341.2 ms | 7340.0 ms | n/a | 8224.8 ms | 0.93 cores | postgres 67% | 0.21 of 18 | server 0.93 | 1.48 | 279.5 KB | - | - | generator-overload 2942 |
| 2-axon-e3-own | axon | 1159.8/s | 1159.8/s | 46392 | 0 | 3.0 ms | 5.3 ms | 38.7 ms | n/a | 233.5 ms | 1.08 cores | postgres 79% | 0.18 of 18 | server 1.08 | 1.61 | 257.6 KB | - | - | - |
| 2-wow-e3-own | wow | 5834.1/s | 5743.3/s | 229733 | 3631 | 68.6 ms | 252.9 ms | 434.2 ms | n/a | 522.2 ms | 2.22 cores | postgres 144% | 1.02 of 18 | server 2.22 | 0.64 | 183.7 KB | - | - | generator-overload 3631 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| m5max-axon-peak@100k | 1-wow-e1-own | steady | **unstable** | second half against first, beyond the default limits: p50 +134.1% (±25.0%) |
| m5max-axon-peak@100k | 1-axon-e1-own | steady | **p99 > 10× p50** | p50 1.2 ms, p99 64.3 ms |
| m5max-axon-peak@100k | 2-wow-e1-own | steady | **unstable** | second half against first, beyond the default limits: p50 +75.8% (±25.0%) |
| m5max-axon-peak@100k | 1-wow-e3-own | steady | **error spike** | 2985 of 233092 failed: generator-overload 2985; generator-overload: 2000 in flight at most (per-target ceiling) |
| m5max-axon-peak@100k | 1-axon-e3-own | steady | **unstable** | second half against first, beyond the default limits: throughput +14.6% (±5.0%), p50 -99.6% (±25.0%) |
| m5max-axon-peak@100k | 1-axon-e3-own | steady | **error spike** | 2942 of 46288 failed: generator-overload 2942; generator-overload: 2000 in flight at most (per-target ceiling) |
| m5max-axon-peak@100k | 1-axon-e3-own | steady | **p99 > 10× p50** | p50 4.0 ms, p99 7340.0 ms |
| m5max-axon-peak@100k | 2-wow-e3-own | steady | **error spike** | 3631 of 233364 failed: generator-overload 3631; generator-overload: 2000 in flight at most (per-target ceiling) |


### m5max-axon-same-rate@100k

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


### m5max-axon-pool-32@100k

Both pools at 32 connections instead of Spring Boot’s 10, on the M5 Max: E1 and E3 to their peaks, W A A W (Axon’s 16 processor threads share 10 connections by default).

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Each sweep in fresh sessions on a runner of its own, the full ABBA: E1 on unknown, ? cores; E3 on unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed; every run on it first waited until the nodes were quiet.

- `wow-pool-32`: spring.datasource.hikari.maximum-pool-size=32
- `axon-pool-32`: spring.datasource.hikari.maximum-pool-size=32

Sessions, in order: 1-wow-pool-32 (E1), 1-axon-pool-32 (E1), 2-axon-pool-32 (E1), 2-wow-pool-32 (E1), 1-wow-pool-32 (E3), 1-axon-pool-32 (E3), 2-axon-pool-32 (E3), 2-wow-pool-32 (E3).

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow-pool-32/axon-pool-32 from the lowest wow-pool-32 peak over the highest axon-pool-32 peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow-pool-32 | axon-pool-32 | wow-pool-32/axon-pool-32 | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37065/s at 2048; 37554/s at 2048 | 14538/s at 512; 14771/s at 512 | 2.51–2.58 | 1.6% | wow-pool-32 higher |
| E3 | 12386/s at 2048; 12510/s at 2048 | 3742/s at 128; 3718/s at 256 | 3.31–3.36 | 1.0% | wow-pool-32 higher |

The load generator used at most 3.09 of its 18 CPUs at a peak, **more than 1.6**; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow-pool-32 | axon-pool-32 | wow-pool-32/axon-pool-32 | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37065/s at 2048 (p99 108.5 ms); 37554/s at 2048 (p99 94.7 ms) | 14538/s at 512 (p99 135.2 ms); 14771/s at 512 (p99 128.5 ms) | 2.51–2.58 | 1.6% | wow-pool-32 higher |
| E3 | 11572/s at 1024 (p99 143.4 ms); 11713/s at 1024 (p99 136.2 ms) | 3684/s at 64 (p99 68.1 ms); 3698/s at 64 (p99 65.3 ms) | 3.13–3.18 | 1.2% | wow-pool-32 higher |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32 (E1) | 100000 (100000) | 5158399 (5157732–5161269) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon-pool-32 (E1) | 100000 (100000) | 3260892 (3260594–3260918) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon-pool-32 (E1) | 100000 (100000) | 2665003 (2664930–2665099) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow-pool-32 (E1) | 100000 (100000) | 5153771 (5153236–5158428) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-wow-pool-32 (E3) | 100000 (100000) | 100001 (100001–100001) | 1729798 (1725281–1893924) | 1729798 (1729798) | 1729798 (1729798) | 1729798 (1729798) | 0 (0) |
| 1-axon-pool-32 (E3) | 100000 (100000) | 100001 (100001–100001) | 812455 (812455–812491) | 812455 (812455) | 812455 (812455) | 812455 (812455) | 0 (0) |
| 2-axon-pool-32 (E3) | 100000 (100000) | 100001 (100001–100001) | 994285 (994272–994312) | 994285 (994285) | 994285 (994285) | 994285 (994285) | 0 (0) |
| 2-wow-pool-32 (E3) | 100000 (100000) | 100001 (100001–100001) | 1739683 (1735536–1927894) | 1739683 (1739683) | 1739683 (1739683) | 1739683 (1739683) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32 (E1) | 5258402 | 5258403 | 3072.0 MB | 613 | 100000 | 46.9 MB | 492 | 1.1 MB |
| 1-axon-pool-32 (E1) | 3360896 | 3360896 | 1465.5 MB | 457 | 0 | 0.0 MB | - | 0.1 MB |
| 2-axon-pool-32 (E1) | 2765007 | 2765007 | 1196.9 MB | 454 | 0 | 0.0 MB | - | 0.1 MB |
| 2-wow-pool-32 (E1) | 5253774 | 5253775 | 3071.8 MB | 613 | 100000 | 47.1 MB | 494 | 1.2 MB |
| 1-wow-pool-32 (E3) | 5389395 | 7119193 | 4065.1 MB | 599 | 100000 | 49.5 MB | 519 | 1.1 MB |
| 1-axon-pool-32 (E3) | 3449821 | 3449821 | 1655.9 MB | 503 | 258 | 0.2 MB | 889 | 1.6 MB |
| 2-axon-pool-32 (E3) | 4177141 | 4177141 | 2009.8 MB | 505 | 1001 | 0.5 MB | 540 | 1.6 MB |
| 2-wow-pool-32 (E3) | 5419050 | 7158733 | 4088.3 MB | 599 | 100000 | 43.9 MB | 460 | 1.4 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow-pool-32 (E1) | 1.8 s | 469.6 MB | 1483.9 MB |
| 1-axon-pool-32 (E1) | 1.1 s | 376.3 MB | 1634.5 MB |
| 2-axon-pool-32 (E1) | 1.1 s | 400.4 MB | 1647.0 MB |
| 2-wow-pool-32 (E1) | 1.8 s | 469.8 MB | 1490.6 MB |
| 1-wow-pool-32 (E3) | 1.8 s | 513.4 MB | 1491.6 MB |
| 1-axon-pool-32 (E3) | 1.2 s | 366.6 MB | 1590.8 MB |
| 2-axon-pool-32 (E3) | 1.1 s | 361.2 MB | 1597.4 MB |
| 2-wow-pool-32 (E3) | 1.8 s | 500.4 MB | 1494.6 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32-e1-sweep | wow-pool-32 | 37065/s at 2048 | peaked below its top step | - | - | - | - | - | 37065/s, p99 108.5 ms | 35965/s, p99 252.9 ms |
| 1-axon-pool-32-e1-sweep | axon-pool-32 | 14538/s at 512 | peaked below its top step | - | - | 14329/s, p99 111.1 ms | 14538/s, p99 135.2 ms | 14177/s, p99 185.3 ms | - | - |
| 2-axon-pool-32-e1-sweep | axon-pool-32 | 14771/s at 512 | peaked below its top step | - | - | 12874/s, p99 147.5 ms | 14771/s, p99 128.5 ms | - | - | - |
| 2-wow-pool-32-e1-sweep | wow-pool-32 | 37554/s at 2048 | levelled off | - | - | - | - | - | 37554/s, p99 94.7 ms | 35576/s, p99 235.5 ms |
| 1-wow-pool-32-e3-sweep | wow-pool-32 | 12386/s at 2048 | more than 1% failed | - | - | - | - | 11572/s, p99 143.4 ms | 12386/s, p99 288.8 ms | - |
| 1-axon-pool-32-e3-sweep | axon-pool-32 | 3742/s at 128 | peaked below its top step | 3684/s, p99 68.1 ms | 3742/s, p99 215.0 ms | 2636/s, p99 708.6 ms | - | - | - | - |
| 2-axon-pool-32-e3-sweep | axon-pool-32 | 3718/s at 256 | peaked below its top step | 3698/s, p99 65.3 ms | 3629/s, p99 212.0 ms | 3718/s, p99 495.6 ms | 3612/s, p99 1556.5 ms | - | - | - |
| 2-wow-pool-32-e3-sweep | wow-pool-32 | 12510/s at 2048 | more than 1% failed | - | - | - | - | 11713/s, p99 136.2 ms | 12510/s, p99 274.4 ms | - |

The short steps that located them (3 s warm-up, 8 s measured), not part of the results:

| Sweep | Arm | 1 clients | 2 clients | 4 clients | 8 clients | 16 clients | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32-e1-sweep | wow-pool-32 | 813/s, p99 3.8 ms | 2129/s, p99 1.2 ms | 2597/s, p99 2.9 ms | 4187/s, p99 3.6 ms | 6556/s, p99 4.9 ms | 9502/s, p99 7.2 ms | 14116/s, p99 9.0 ms | 18282/s, p99 14.3 ms | 18191/s, p99 25.2 ms | 17930/s, p99 40.4 ms | 26355/s, p99 66.6 ms | 37679/s, p99 116.7 ms | 36757/s, p99 286.7 ms |
| 1-axon-pool-32-e1-sweep | axon-pool-32 | 497/s, p99 4.4 ms | 557/s, p99 6.2 ms | 3409/s, p99 3.8 ms | 5874/s, p99 2.0 ms | 9269/s, p99 2.6 ms | 13062/s, p99 5.1 ms | 13833/s, p99 10.4 ms | 13865/s, p99 35.6 ms | 14533/s, p99 111.1 ms | 14788/s, p99 125.4 ms | 14345/s, p99 164.9 ms | 14006/s, p99 259.1 ms | 13556/s, p99 421.9 ms |
| 2-axon-pool-32-e1-sweep | axon-pool-32 | 1381/s, p99 1.1 ms | 2146/s, p99 1.3 ms | 3635/s, p99 1.7 ms | 5935/s, p99 2.0 ms | 9313/s, p99 2.5 ms | 12737/s, p99 4.6 ms | 14124/s, p99 11.0 ms | 14015/s, p99 35.1 ms | 14928/s, p99 113.2 ms | 14612/s, p99 128.5 ms | 14913/s, p99 169.0 ms | 14301/s, p99 255.0 ms | 13284/s, p99 438.3 ms |
| 2-wow-pool-32-e1-sweep | wow-pool-32 | 541/s, p99 4.2 ms | 2125/s, p99 1.2 ms | 2602/s, p99 3.9 ms | 4161/s, p99 3.7 ms | 6590/s, p99 4.8 ms | 9504/s, p99 7.5 ms | 14074/s, p99 9.3 ms | 18676/s, p99 13.9 ms | 18193/s, p99 25.2 ms | 17764/s, p99 47.4 ms | 24436/s, p99 63.2 ms | 36908/s, p99 174.1 ms | 38154/s, p99 190.5 ms |
| 1-wow-pool-32-e3-sweep | wow-pool-32 | 179/s, p99 13.3 ms | 630/s, p99 9.8 ms | 1107/s, p99 6.3 ms | 1804/s, p99 7.2 ms | 2899/s, p99 9.6 ms | 3202/s, p99 23.4 ms | 5511/s, p99 19.5 ms | 6737/s, p99 33.5 ms | 5648/s, p99 64.0 ms | 8015/s, p99 90.6 ms | 12525/s, p99 129.0 ms | 14117/s, p99 228.4 ms | 6797/s, 37988 errors |
| 1-axon-pool-32-e3-sweep | axon-pool-32 | 192/s, p99 13.4 ms | 806/s, p99 3.9 ms | 1317/s, p99 8.1 ms | 2161/s, p99 7.4 ms | 2995/s, p99 13.4 ms | 3484/s, p99 29.7 ms | 3745/s, p99 62.7 ms | 3881/s, p99 145.4 ms | 3890/s, p99 294.9 ms | 3708/s, p99 892.9 ms | 3787/s, p99 1589.2 ms | 3674/s, p99 1654.8 ms | 3249/s, p99 1900.5 ms |
| 2-axon-pool-32-e3-sweep | axon-pool-32 | 518/s, p99 2.7 ms | 816/s, p99 3.7 ms | 1301/s, p99 9.1 ms | 2166/s, p99 7.1 ms | 2952/s, p99 13.8 ms | 3440/s, p99 32.8 ms | 3750/s, p99 61.7 ms | 3306/s, p99 189.4 ms | 3851/s, p99 350.2 ms | 3780/s, p99 593.9 ms | 3817/s, p99 819.2 ms | 3689/s, p99 1826.8 ms | 3262/s, p99 2097.2 ms |
| 2-wow-pool-32-e3-sweep | wow-pool-32 | 455/s, p99 7.2 ms | 821/s, p99 3.7 ms | 1116/s, p99 6.7 ms | 1839/s, p99 7.0 ms | 2843/s, p99 9.9 ms | 3994/s, p99 14.0 ms | 5279/s, p99 20.2 ms | 6135/s, p99 61.4 ms | 5593/s, p99 69.1 ms | 7773/s, p99 101.9 ms | 12481/s, p99 137.2 ms | 14025/s, p99 232.4 ms | 7480/s, 71171 errors |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


### m5max-axon-contention@100k

Contention on the M5 Max: E1 and E3 with Zipf keys and E1 on hot keys to their peaks, Wow with no bus against Axon Framework 4, W A A W.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Each sweep in fresh sessions on a runner of its own, the full ABBA: E1 zipf on unknown, ? cores; E3 zipf on unknown, ? cores; E1 hot on unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed; every run on it first waited until the nodes were quiet.

Sessions, in order: 1-wow (E1 zipf), 1-axon (E1 zipf), 2-axon (E1 zipf), 2-wow (E1 zipf), 1-wow (E3 zipf), 1-axon (E3 zipf), 2-axon (E3 zipf), 2-wow (E3 zipf), 1-wow (E1 hot), 1-axon (E1 hot), 2-axon (E1 hot), 2-wow (E1 hot).

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 zipf | 23812/s at 2048; ≥ 23352/s at 2048 (top step) | 6142/s at 512; 6220/s at 256 | ≥ 3.75–3.88 | 2.0% | not comparable (wow: the store check) |
| E3 zipf | 2447/s at 64; 2562/s at 32 | 1785/s at 32; 1769/s at 64 | 1.37–1.45 | 4.7% | not comparable (wow: the store check) |
| E1 hot | ≥ 23943/s at 2048 (top step); ≥ 23634/s at 2048 (top step) | 2654/s at 256; 2633/s at 256 | ≥ 8.90–9.09 | 1.3% | not comparable (wow: the store check) |

The load generator used at most 3.77 of its 18 CPUs at a peak, **more than 1.6**; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 zipf | 19837/s at 512 (p99 135.2 ms); 18781/s at 512 (p99 144.4 ms) | 6142/s at 512 (p99 182.3 ms); 6220/s at 256 (p99 132.1 ms) | 3.02–3.23 | 5.6% | not comparable (wow: the store check) |
| E3 zipf | 2447/s at 64 (p99 169.0 ms); 2562/s at 32 (p99 65.0 ms) | 1785/s at 32 (p99 90.1 ms); 1769/s at 64 (p99 189.4 ms) | 1.37–1.45 | 4.7% | not comparable (wow: the store check) |
| E1 hot | 14274/s at 128 (p99 19.5 ms); 13618/s at 256 (p99 49.7 ms) | 2654/s at 256 (p99 177.2 ms); 2633/s at 256 (p99 178.2 ms) | 5.13–5.42 | 4.8% | not comparable (wow: the store check) |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit). **Not comparable: wow.**

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow (E1 zipf) | 100000 (100000) | 4398198 (4398017–4466207) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon (E1 zipf) | 100000 (100000) | 1275537 (1275005–1275595) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon (E1 zipf) | 100000 (100000) | 1579035 (1578758–1579279) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow (E1 zipf) | 100000 (100000) | 4095741 (4095741–4122972) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-wow (E3 zipf) | 100000 (100000) | 100001 (100001–100001) | 555013 (547513–616749) | 555013 (555013) | **552570 (555013)** | **548931 (552570)** | 0 (0) |
| 1-axon (E3 zipf) | 100000 (100000) | 100001 (100001–100001) | 568898 (567186–569365) | 568898 (568898) | 568898 (568898) | 568898 (568898) | 0 (0) |
| 2-axon (E3 zipf) | 100000 (100000) | 100001 (100001–100001) | 492397 (491299–494074) | 492397 (492397) | 492397 (492397) | 492397 (492397) | 0 (0) |
| 2-wow (E3 zipf) | 100000 (100000) | 100001 (100001–100001) | 740979 (728367–796440) | 740979 (740979) | 740979 (740979) | 740979 (740979) | 0 (0) |
| 1-wow (E1 hot) | 100000 (100000) | 4120612 (4120548–5134824) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon (E1 hot) | 100000 (100000) | 649967 (649787–650019) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon (E1 hot) | 100000 (100000) | 661493 (661320–661538) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow (E1 hot) | 100000 (100000) | 3863025 (3862965–4911069) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow (E1 zipf) | 4498201 | 4498202 | 2692.4 MB | 628 | 100000 | 41.8 MB | 438 | 1.4 MB |
| 1-axon (E1 zipf) | 1375541 | 1375541 | 617.5 MB | 471 | 820 | 0.9 MB | 1129 | 0.1 MB |
| 2-axon (E1 zipf) | 1679039 | 1679039 | 753.2 MB | 470 | 1018 | 0.9 MB | 958 | 0.1 MB |
| 2-wow (E1 zipf) | 4195744 | 4195745 | 2513.4 MB | 628 | 100000 | 41.9 MB | 439 | 1.4 MB |
| 1-wow (E3 zipf) | 1856515 | 2411528 | 1415.7 MB | 616 | 100000 | 41.7 MB | 438 | 1.6 MB |
| 1-axon (E3 zipf) | 2475593 | 2475593 | 1212.3 MB | 513 | 1607 | 1.2 MB | 785 | 0.6 MB |
| 2-axon (E3 zipf) | 2169589 | 2169589 | 1061.9 MB | 513 | 1404 | 1.2 MB | 869 | 0.6 MB |
| 2-wow (E3 zipf) | 2422938 | 3163917 | 1853.0 MB | 614 | 100000 | 41.9 MB | 439 | 1.6 MB |
| 1-wow (E1 hot) | 4220615 | 4220616 | 2495.6 MB | 620 | 100000 | 41.9 MB | 440 | 1.2 MB |
| 1-axon (E1 hot) | 749971 | 749971 | 338.5 MB | 473 | 1 | 0.3 MB | 278528 | 0.0 MB |
| 2-axon (E1 hot) | 761497 | 761497 | 343.8 MB | 473 | 1 | 0.3 MB | 278528 | 0.1 MB |
| 2-wow (E1 hot) | 3963028 | 3963029 | 2342.8 MB | 620 | 100000 | 42.0 MB | 440 | 1.3 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow (E1 zipf) | 1.8 s | 464.7 MB | 1494.6 MB |
| 1-axon (E1 zipf) | 1.1 s | 378.9 MB | 1616.2 MB |
| 2-axon (E1 zipf) | 1.1 s | 378.9 MB | 1610.2 MB |
| 2-wow (E1 zipf) | 1.8 s | 471.1 MB | 1475.4 MB |
| 1-wow (E3 zipf) | 1.8 s | 462.5 MB | 1527.1 MB |
| 1-axon (E3 zipf) | 1.1 s | 403.2 MB | 1593.2 MB |
| 2-axon (E3 zipf) | 1.1 s | 382.0 MB | 1605.4 MB |
| 2-wow (E3 zipf) | 1.8 s | 475.7 MB | 1509.8 MB |
| 1-wow (E1 hot) | 1.8 s | 465.4 MB | 1462.4 MB |
| 1-axon (E1 hot) | 1.2 s | 371.2 MB | 1585.0 MB |
| 2-axon (E1 hot) | 1.1 s | 397.6 MB | 1610.1 MB |
| 2-wow (E1 hot) | 1.8 s | 470.4 MB | 1483.6 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-zipf-sweep | wow | 23812/s at 2048 | more than 1% failed | - | - | - | - | 19837/s, p99 135.2 ms | 19266/s, p99 346.1 ms | 23812/s, 12528 errors | - |
| 1-axon-e1-zipf-sweep | axon | 6142/s at 512 | peaked below its top step | - | - | - | 6133/s, p99 135.2 ms | 6142/s, p99 182.3 ms | - | - | - |
| 2-axon-e1-zipf-sweep | axon | 6220/s at 256 | peaked below its top step | - | - | - | 6220/s, p99 132.1 ms | 6143/s, p99 181.2 ms | 6049/s, p99 307.2 ms | - | - |
| 2-wow-e1-zipf-sweep | wow | ≥ 23352/s at 2048 (top step) | more than 1% failed | - | - | - | - | 18781/s, p99 144.4 ms | 19236/s, p99 362.5 ms | 23352/s, 18769 errors | - |
| 1-wow-e3-zipf-sweep | wow | 2447/s at 64 | more than 1% failed | 1984/s, p99 129.5 ms | 2447/s, p99 169.0 ms | 102/s, 384 errors | - | - | - | - | 871/s, 3822 errors in 10 s |
| 1-axon-e3-zipf-sweep | axon | 1785/s at 32 | levelled off | 1785/s, p99 90.1 ms | 1759/s, p99 202.8 ms | 1750/s, p99 405.5 ms | - | - | - | 1713/s, p99 6553.6 ms | 1748/s, 1083 errors in 15 s |
| 2-axon-e3-zipf-sweep | axon | 1769/s at 64 | levelled off | - | 1769/s, p99 189.4 ms | 1623/s, p99 643.1 ms | - | - | - | 1719/s, p99 6717.4 ms | 1765/s, 652 errors in 13 s |
| 2-wow-e3-zipf-sweep | wow | 2562/s at 32 | more than 1% failed | 2562/s, p99 65.0 ms | 1995/s, p99 335.9 ms | 2302/s, p99 630.8 ms | - | - | - | - | 2543/s, 8313 errors |
| 1-wow-e1-hot-sweep | wow | ≥ 23943/s at 2048 (top step) | more than 1% failed | - | - | 14274/s, p99 19.5 ms | 10978/s, p99 209.9 ms | 9434/s, p99 274.4 ms | 11510/s, p99 374.8 ms | 23943/s, 619015 errors | - |
| 1-axon-e1-hot-sweep | axon | 2654/s at 256 | peaked below its top step | - | - | - | 2654/s, p99 177.2 ms | 2638/s, p99 288.8 ms | - | - | - |
| 2-axon-e1-hot-sweep | axon | 2633/s at 256 | peaked below its top step | - | - | - | 2633/s, p99 178.2 ms | 2598/s, p99 297.0 ms | - | - | - |
| 2-wow-e1-hot-sweep | wow | ≥ 23634/s at 2048 (top step) | more than 1% failed | - | - | - | 13618/s, p99 49.7 ms | 12997/s, p99 119.8 ms | 12539/s, p99 364.5 ms | 23634/s, 622881 errors | - |

The short steps that located them (3 s warm-up, 8 s measured), not part of the results:

| Sweep | Arm | 1 clients | 2 clients | 4 clients | 8 clients | 16 clients | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients | 8192 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-zipf-sweep | wow | 1447/s, p99 1.1 ms | 2138/s, p99 1.3 ms | 2640/s, p99 2.7 ms | 2953/s, p99 9.3 ms | 5540/s, p99 8.2 ms | 9221/s, p99 8.0 ms | 13348/s, p99 10.9 ms | 17426/s, p99 16.9 ms | 19098/s, p99 36.9 ms | 20095/s, p99 136.2 ms | 19983/s, p99 335.9 ms | 24051/s, 1078 errors | 26385/s, 28564 errors | - |
| 1-axon-e1-zipf-sweep | axon | 1351/s, p99 1.3 ms | 2082/s, p99 1.5 ms | 2088/s, p99 6.0 ms | 5123/s, p99 4.7 ms | 5772/s, p99 9.7 ms | 6125/s, p99 12.5 ms | 6094/s, p99 26.9 ms | 5950/s, p99 53.0 ms | 6246/s, p99 129.5 ms | 6238/s, p99 171.0 ms | 4016/s, p99 745.5 ms | 6240/s, p99 419.8 ms | 3891/s, p99 2277.4 ms | - |
| 2-axon-e1-zipf-sweep | axon | 1367/s, p99 1.3 ms | 2093/s, p99 1.8 ms | 3576/s, p99 2.2 ms | 5203/s, p99 4.6 ms | 5816/s, p99 9.3 ms | 6071/s, p99 13.0 ms | 6069/s, p99 28.0 ms | 6022/s, p99 52.5 ms | 6256/s, p99 130.0 ms | 6250/s, p99 170.0 ms | 6302/s, p99 252.9 ms | 6191/s, p99 430.1 ms | 6077/s, p99 1269.8 ms | - |
| 2-wow-e1-zipf-sweep | wow | 1444/s, p99 1.1 ms | 1164/s, p99 5.1 ms | 2618/s, p99 2.8 ms | 3803/s, p99 4.9 ms | 6064/s, p99 6.0 ms | 9208/s, p99 8.0 ms | 13441/s, p99 10.8 ms | 17311/s, p99 16.9 ms | 18769/s, p99 37.9 ms | 19998/s, p99 139.3 ms | 20909/s, p99 315.4 ms | 24760/s, 3144 errors | - | - |
| 1-wow-e3-zipf-sweep | wow | 601/s, p99 2.2 ms | 206/s, p99 23.4 ms | 1134/s, p99 5.9 ms | 1655/s, p99 9.9 ms | 2468/s, p99 18.3 ms | 2660/s, p99 61.2 ms | 2632/s, p99 153.6 ms | 2603/s, p99 348.2 ms | 2484/s, p99 905.2 ms | 2570/s, p99 1564.7 ms | 2567/s, p99 2883.6 ms | 2879/s, p99 5177.3 ms | 3398/s, p99 7143.4 ms | 3666/s, 695 errors |
| 1-axon-e3-zipf-sweep | axon | 514/s, p99 2.7 ms | 799/s, p99 4.3 ms | 1285/s, p99 5.6 ms | 1766/s, p99 12.0 ms | 1744/s, p99 39.9 ms | 1806/s, p99 93.2 ms | 1825/s, p99 197.6 ms | 1785/s, p99 405.5 ms | 1775/s, p99 794.6 ms | 1746/s, p99 1630.2 ms | 1803/s, p99 3211.3 ms | 1851/s, p99 5079.0 ms | 1863/s, p99 7045.1 ms | - |
| 2-axon-e3-zipf-sweep | axon | 511/s, p99 2.8 ms | 789/s, p99 5.0 ms | 1289/s, p99 5.6 ms | 1785/s, p99 12.4 ms | 1775/s, p99 38.4 ms | 1818/s, p99 89.6 ms | 1824/s, p99 196.6 ms | 1743/s, p99 434.2 ms | 1793/s, p99 774.1 ms | 1739/s, p99 1622.0 ms | 1777/s, p99 3227.6 ms | 1866/s, p99 5079.0 ms | 1878/s, p99 6946.8 ms | - |
| 2-wow-e3-zipf-sweep | wow | 604/s, p99 2.2 ms | 807/s, p99 3.8 ms | 1126/s, p99 6.0 ms | 1633/s, p99 10.8 ms | 2269/s, p99 21.4 ms | 2455/s, p99 69.6 ms | 2598/s, p99 156.7 ms | 2531/s, p99 354.3 ms | 2471/s, p99 901.1 ms | 2571/s, p99 1458.2 ms | 2552/s, p99 2932.7 ms | 2858/s, p99 5341.2 ms | 3458/s, p99 6979.6 ms | 3850/s, 454 errors |
| 1-wow-e1-hot-sweep | wow | 711/s, p99 3.6 ms | 2015/s, p99 1.6 ms | 2625/s, p99 2.7 ms | 3787/s, p99 4.5 ms | 5915/s, p99 5.4 ms | 8958/s, p99 7.4 ms | 7720/s, p99 20.9 ms | 14500/s, p99 19.1 ms | 10887/s, p99 205.8 ms | 14252/s, p99 105.5 ms | 14844/s, p99 151.6 ms | 24155/s, 165974 errors | - | - |
| 1-axon-e1-hot-sweep | axon | 1370/s, p99 1.2 ms | 1934/s, p99 1.9 ms | 2459/s, p99 3.2 ms | 2413/s, p99 7.9 ms | 2538/s, p99 20.6 ms | 2622/s, p99 28.9 ms | 939/s, p99 260.1 ms | 2558/s, p99 139.3 ms | 2697/s, p99 173.1 ms | 2634/s, p99 274.4 ms | 2695/s, p99 460.8 ms | 2657/s, p99 860.2 ms | 2570/s, p99 1794.0 ms | - |
| 2-axon-e1-hot-sweep | axon | 1346/s, p99 1.3 ms | 1942/s, p99 2.0 ms | 2428/s, p99 3.3 ms | 2385/s, p99 8.1 ms | 2255/s, p99 26.4 ms | 2613/s, p99 28.0 ms | 2620/s, p99 42.5 ms | 2597/s, p99 101.9 ms | 2670/s, p99 173.1 ms | 2642/s, p99 274.4 ms | 2655/s, p99 469.0 ms | 2593/s, p99 876.5 ms | 2552/s, p99 1851.4 ms | - |
| 2-wow-e1-hot-sweep | wow | 1259/s, p99 2.8 ms | 1985/s, p99 1.6 ms | 2612/s, p99 2.7 ms | 3782/s, p99 4.7 ms | 5917/s, p99 5.4 ms | 8954/s, p99 7.3 ms | 12680/s, p99 10.6 ms | 14700/s, p99 18.8 ms | 14722/s, p99 41.5 ms | 12902/s, p99 278.5 ms | 11097/s, p99 380.9 ms | 23773/s, 175181 errors | - | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| m5max-axon-contention@100k | 1-wow (E3 zipf) | store | **store does not match the runs** | transfers entered or refused: 552570 stored, 555013 expected |
| m5max-axon-contention@100k | 1-wow (E3 zipf) | store | **store does not match the runs** | entries confirmed: 548931 stored, 552570 expected |


