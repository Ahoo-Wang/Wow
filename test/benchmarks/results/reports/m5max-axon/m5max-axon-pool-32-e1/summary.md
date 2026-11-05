## m5max-axon-pool-32@100k

Both pools at 32 connections instead of Spring Boot’s 10, on the M5 Max: E1 and E3 to their peaks, W A A W (Axon’s 16 processor threads share 10 connections by default).

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Runner: unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed; every run on it first waited until the nodes were quiet.

- `wow-pool-32`: spring.datasource.hikari.maximum-pool-size=32
- `axon-pool-32`: spring.datasource.hikari.maximum-pool-size=32

Sessions, in order: 1-wow-pool-32, 1-axon-pool-32, 2-axon-pool-32, 2-wow-pool-32.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow-pool-32/axon-pool-32 from the lowest wow-pool-32 peak over the highest axon-pool-32 peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow-pool-32 | axon-pool-32 | wow-pool-32/axon-pool-32 | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37065/s at 2048; 37554/s at 2048 | 14538/s at 512; 14771/s at 512 | 2.51–2.58 | 1.6% | wow-pool-32 higher |

The load generator used at most 3.09 of its 18 CPUs at a peak, **more than 1.6**; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow-pool-32 | axon-pool-32 | wow-pool-32/axon-pool-32 | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 37065/s at 2048 (p99 108.5 ms); 37554/s at 2048 (p99 94.7 ms) | 14538/s at 512 (p99 135.2 ms); 14771/s at 512 (p99 128.5 ms) | 2.51–2.58 | 1.6% | wow-pool-32 higher |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32 | 100000 (100000) | 5158399 (5157732–5161269) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon-pool-32 | 100000 (100000) | 3260892 (3260594–3260918) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon-pool-32 | 100000 (100000) | 2665003 (2664930–2665099) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow-pool-32 | 100000 (100000) | 5153771 (5153236–5158428) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32 | 5258402 | 5258403 | 3072.0 MB | 613 | 100000 | 46.9 MB | 492 | 1.1 MB |
| 1-axon-pool-32 | 3360896 | 3360896 | 1465.5 MB | 457 | 0 | 0.0 MB | - | 0.1 MB |
| 2-axon-pool-32 | 2765007 | 2765007 | 1196.9 MB | 454 | 0 | 0.0 MB | - | 0.1 MB |
| 2-wow-pool-32 | 5253774 | 5253775 | 3071.8 MB | 613 | 100000 | 47.1 MB | 494 | 1.2 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow-pool-32 | 1.8 s | 469.6 MB | 1483.9 MB |
| 1-axon-pool-32 | 1.1 s | 376.3 MB | 1634.5 MB |
| 2-axon-pool-32 | 1.1 s | 400.4 MB | 1647.0 MB |
| 2-wow-pool-32 | 1.8 s | 469.8 MB | 1490.6 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32-e1-sweep | wow-pool-32 | 37065/s at 2048 | peaked below its top step | - | - | - | 37065/s, p99 108.5 ms | 35965/s, p99 252.9 ms |
| 1-axon-pool-32-e1-sweep | axon-pool-32 | 14538/s at 512 | peaked below its top step | 14329/s, p99 111.1 ms | 14538/s, p99 135.2 ms | 14177/s, p99 185.3 ms | - | - |
| 2-axon-pool-32-e1-sweep | axon-pool-32 | 14771/s at 512 | peaked below its top step | 12874/s, p99 147.5 ms | 14771/s, p99 128.5 ms | - | - | - |
| 2-wow-pool-32-e1-sweep | wow-pool-32 | 37554/s at 2048 | levelled off | - | - | - | 37554/s, p99 94.7 ms | 35576/s, p99 235.5 ms |

The short steps that located them (3 s warm-up, 8 s measured), not part of the results:

| Sweep | Arm | 1 clients | 2 clients | 4 clients | 8 clients | 16 clients | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32-e1-sweep | wow-pool-32 | 813/s, p99 3.8 ms | 2129/s, p99 1.2 ms | 2597/s, p99 2.9 ms | 4187/s, p99 3.6 ms | 6556/s, p99 4.9 ms | 9502/s, p99 7.2 ms | 14116/s, p99 9.0 ms | 18282/s, p99 14.3 ms | 18191/s, p99 25.2 ms | 17930/s, p99 40.4 ms | 26355/s, p99 66.6 ms | 37679/s, p99 116.7 ms | 36757/s, p99 286.7 ms |
| 1-axon-pool-32-e1-sweep | axon-pool-32 | 497/s, p99 4.4 ms | 557/s, p99 6.2 ms | 3409/s, p99 3.8 ms | 5874/s, p99 2.0 ms | 9269/s, p99 2.6 ms | 13062/s, p99 5.1 ms | 13833/s, p99 10.4 ms | 13865/s, p99 35.6 ms | 14533/s, p99 111.1 ms | 14788/s, p99 125.4 ms | 14345/s, p99 164.9 ms | 14006/s, p99 259.1 ms | 13556/s, p99 421.9 ms |
| 2-axon-pool-32-e1-sweep | axon-pool-32 | 1381/s, p99 1.1 ms | 2146/s, p99 1.3 ms | 3635/s, p99 1.7 ms | 5935/s, p99 2.0 ms | 9313/s, p99 2.5 ms | 12737/s, p99 4.6 ms | 14124/s, p99 11.0 ms | 14015/s, p99 35.1 ms | 14928/s, p99 113.2 ms | 14612/s, p99 128.5 ms | 14913/s, p99 169.0 ms | 14301/s, p99 255.0 ms | 13284/s, p99 438.3 ms |
| 2-wow-pool-32-e1-sweep | wow-pool-32 | 541/s, p99 4.2 ms | 2125/s, p99 1.2 ms | 2602/s, p99 3.9 ms | 4161/s, p99 3.7 ms | 6590/s, p99 4.8 ms | 9504/s, p99 7.5 ms | 14074/s, p99 9.3 ms | 18676/s, p99 13.9 ms | 18193/s, p99 25.2 ms | 17764/s, p99 47.4 ms | 24436/s, p99 63.2 ms | 36908/s, p99 174.1 ms | 38154/s, p99 190.5 ms |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

