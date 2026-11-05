## m5max-axon-peak@100k

The industry comparison on the M5 Max: Wow with no bus (wow.transport=none) against Axon Framework 4 on one PostgreSQL, one node each, W A A W: closed-loop sweeps of E1 and E3 to their peaks, then each at half of its own sustainable rate.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Runner: unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed (7–14 s); every run on it first waited until the nodes were quiet (3–7 s).

Sessions, in order: 1-wow, 1-axon, 2-axon, 2-wow.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37311/s at 2048; 37344/s at 2048 | 7725/s at 256; 7716/s at 256 | 4.83–4.84 | 0.1% | wow higher |

The load generator used at most 3.10 of its 18 CPUs at a peak, **more than 1.6**; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37311/s at 2048 (p99 99.3 ms); 37344/s at 2048 (p99 105.5 ms) | 7725/s at 256 (p99 126.5 ms); 7716/s at 256 (p99 126.5 ms) | 4.83–4.84 | 0.1% | wow higher |

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

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 100000 (100000) | 6322648 (6322608–6325024) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon | 100000 (100000) | 1770571 (1770240–1771147) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon | 100000 (100000) | 1773704 (1773623–1774073) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow | 100000 (100000) | 6320876 (6320876–6322338) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 6422651 | 6422652 | 3764.5 MB | 615 | 100000 | 47.8 MB | 502 | 1.3 MB |
| 1-axon | 1870575 | 1870575 | 824.0 MB | 462 | 0 | 0.0 MB | - | 0.1 MB |
| 2-axon | 1873708 | 1873708 | 826.0 MB | 462 | 0 | 0.0 MB | - | 0.1 MB |
| 2-wow | 6420879 | 6420880 | 3763.6 MB | 615 | 100000 | 48.0 MB | 503 | 1.6 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow | 1.8 s | 460.6 MB | 1508.1 MB |
| 1-axon | 1.1 s | 370.6 MB | 1586.3 MB |
| 2-axon | 1.2 s | 384.6 MB | 1575.7 MB |
| 2-wow | 1.8 s | 487.4 MB | 1496.6 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 256 clients | 512 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-sweep | wow | 37311/s at 2048 | peaked below its top step | - | - | 37311/s, p99 99.3 ms | 36073/s, p99 250.9 ms |
| 1-axon-e1-sweep | axon | 7725/s at 256 | peaked below its top step | 7725/s, p99 126.5 ms | 7640/s, p99 161.8 ms | - | - |
| 2-axon-e1-sweep | axon | 7716/s at 256 | peaked below its top step | 7716/s, p99 126.5 ms | 5538/s, p99 378.9 ms | - | - |
| 2-wow-e1-sweep | wow | 37344/s at 2048 | peaked below its top step | - | - | 37344/s, p99 105.5 ms | 36320/s, p99 200.7 ms |

The short steps that located them (3 s warm-up, 8 s measured), not part of the results:

| Sweep | Arm | 1 clients | 2 clients | 4 clients | 8 clients | 16 clients | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-sweep | wow | 1475/s, p99 1.0 ms | 2159/s, p99 1.2 ms | 2678/s, p99 2.5 ms | 4062/s, p99 5.5 ms | 6636/s, p99 4.8 ms | 9970/s, p99 6.5 ms | 14168/s, p99 9.0 ms | 18662/s, p99 13.6 ms | 18701/s, p99 24.3 ms | 18026/s, p99 43.8 ms | 24969/s, p99 67.6 ms | 38973/s, p99 84.0 ms | 36739/s, p99 214.0 ms |
| 1-axon-e1-sweep | axon | 1390/s, p99 1.2 ms | 2103/s, p99 2.1 ms | 3720/s, p99 1.7 ms | 5295/s, p99 4.3 ms | 5191/s, p99 10.3 ms | 7384/s, p99 7.6 ms | 7477/s, p99 25.1 ms | 7430/s, p99 47.4 ms | 7916/s, p99 115.2 ms | 7688/s, p99 156.7 ms | 7766/s, p99 222.2 ms | 7705/s, p99 358.4 ms | 7585/s, p99 634.9 ms |
| 2-axon-e1-sweep | axon | 1383/s, p99 1.1 ms | 1672/s, p99 4.5 ms | 3676/s, p99 1.7 ms | 5850/s, p99 2.6 ms | 6997/s, p99 5.2 ms | 7542/s, p99 7.3 ms | 7558/s, p99 20.7 ms | 7470/s, p99 45.6 ms | 7886/s, p99 123.9 ms | 7785/s, p99 155.6 ms | 7697/s, p99 222.2 ms | 7680/s, p99 360.4 ms | 7551/s, p99 634.9 ms |
| 2-wow-e1-sweep | wow | 707/s, p99 3.9 ms | 2129/s, p99 1.2 ms | 2676/s, p99 2.5 ms | 4041/s, p99 4.5 ms | 6516/s, p99 4.9 ms | 9844/s, p99 6.4 ms | 13918/s, p99 9.2 ms | 18424/s, p99 14.5 ms | 17744/s, p99 25.9 ms | 17793/s, p99 43.0 ms | 26868/s, p99 64.5 ms | 38787/s, p99 81.4 ms | 36613/s, p99 247.8 ms |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-own | wow | 18655.7/s | 18558.5/s | 742341 | 3887 | 16.1 ms | 54.5 ms | 77.3 ms | n/a | 234.5 ms | 3.83 cores | postgres 179% | 2.75 of 18 | server 3.83 | 0.30 | 75.7 KB | - | - | generator-overload 3887 |
| 1-axon-e1-own | axon | 3862.5/s | 3862.5/s | 154500 | 0 | 1.2 ms | 2.1 ms | 64.3 ms | n/a | 297.0 ms | 1.11 cores | postgres 73% | 0.61 of 18 | server 1.11 | 0.47 | 79.0 KB | - | - | - |
| 2-axon-e1-own | axon | 3858.1/s | 3858.1/s | 154324 | 0 | 1.2 ms | 1.8 ms | 9.3 ms | n/a | 77.8 ms | 1.16 cores | postgres 73% | 0.67 of 18 | server 1.16 | 0.49 | 78.9 KB | - | - | - |
| 2-wow-e1-own | wow | 18671.9/s | 18599.8/s | 743994 | 2881 | 16.5 ms | 54.5 ms | 68.6 ms | n/a | 280.6 ms | 3.84 cores | postgres 178% | 2.74 of 18 | server 3.84 | 0.30 | 75.7 KB | - | - | generator-overload 2881 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| m5max-axon-peak@100k | 1-wow-e1-own | steady | **unstable** | second half against first, beyond the default limits: p50 +134.1% (±25.0%) |
| m5max-axon-peak@100k | 1-axon-e1-own | steady | **p99 > 10× p50** | p50 1.2 ms, p99 64.3 ms |
| m5max-axon-peak@100k | 2-wow-e1-own | steady | **unstable** | second half against first, beyond the default limits: p50 +75.8% (±25.0%) |

