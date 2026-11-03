## axon-pg-segments@100k

Axon's tracking processor on a runner: 16 segments and threads, axon-server's own, against 2, the server's CPUs, by their peaks and E3 at one rate.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×4 a step to 4096 and on while it rises, to 16384 at most; 2 round(s) of one session per arm, ABBA. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed (49–54 s); every run on it first waited until the nodes were quiet (4–4 s).

- `axon-16`: benchmark.axon.segments=16
- `axon-2`: benchmark.axon.segments=2

Sessions, in order: 1-axon-16, 1-axon-2, 2-axon-2, 2-axon-16.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; axon-16/axon-2 from the lowest axon-16 peak over the highest axon-2 peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | axon-16 | axon-2 | axon-16/axon-2 | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | ≥ 3089/s at 4096 (top step); 3040/s at 1024 | 2994/s at 256; 3071/s at 1024 | ≥ 0.99–1.03 | 2.6% | undetermined (a lower bound) |
| E3 | 798/s at 256; 797/s at 64 | 680/s at 4096; 688/s at 4096 | 1.16–1.17 | 1.2% | axon-16 higher |

The load generator used at most 0.58 of its 2 CPUs at a peak, and Kafka, idle on the same CPUs, at most 0.59 cores in an open-loop run: 1.16 cores together.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | axon-16 | axon-2 | axon-16/axon-2 | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 2843/s at 64 (p99 76.8 ms); 2781/s at 64 (p99 86.5 ms) | 2827/s at 64 (p99 88.6 ms); 2949/s at 64 (p99 72.7 ms) | 0.94–1.01 | 4.3% | level (within the A/A spread) |
| E3 | 760/s at 16 (p99 50.2 ms); 751/s at 16 (p99 48.6 ms) | 617/s at 16 (p99 57.3 ms); 567/s at 16 (p99 62.7 ms) | 1.22–1.34 | 8.8% | axon-16 higher |

**Same rate**: for the same work, whose latency and cost are lower. Both arms at one rate, before the sweeps (20 s warm-up, 40 s measured), in session order; kept up: at most 1% failed and at least 97% of the rate completed.

- E3 at 300/s: 53% of the lowest sustainable rate a sweep found here (axon-2, 567/s).

| Scenario | Metric | axon-16 | axon-2 |
| --- | --- | --- | --- |
| E3 | Rate | 300/s, 300/s | 300/s, 300/s |
| E3 | Throughput | 300.0/s, 300.0/s | 300.0/s, 300.0/s |
| E3 | Kept up | yes, yes | yes, yes |
| E3 | Failed | 0, 0 | 0, 0 |
| E3 | p50 | 4.13 ms, 3.98 ms | 3.38 ms, 3.44 ms |
| E3 | p99 | 16.3 ms, 48.4 ms | 29.1 ms, 19.5 ms |
| E3 | Node CPU ms/op | 2.403, 2.432 | 1.870, 1.874 |
| E3 | PostgreSQL CPU ms/op | 2.746, 2.670 | 2.790, 2.754 |
| E3 | PostgreSQL transactions/op | 6.65, 6.63 | 6.87, 7.13 |
| E3 | PostgreSQL WAL bytes/op | 3.1 KB, 3.1 KB | 3.0 KB, 3.1 KB |
| E3 | Node alloc/op | 263.2 KB, 263.1 KB | 196.2 KB, 195.9 KB |
| E3 | Node GC time | 0.4%, 1.1% | 0.8%, 0.4% |
| E3 | Node RSS max | 1020 MB, 1140 MB | 1289 MB, 1006 MB |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-axon-16 | 100000 (100000) | 769912 (769912–769912) | 205945 (205866–205945) | 205945 (205945) | 205945 (205945) | 205945 (205945) | 0 (0) |
| 1-axon-2 | 100000 (100000) | 771213 (771213–771213) | 244576 (183407–244576) | 244576 (244576) | 244576 (244576) | 244576 (244576) | 0 (0) |
| 2-axon-2 | 100000 (100000) | 787773 (787773–787773) | 244690 (181827–244690) | 244690 (244690) | 244690 (244690) | 244690 (244690) | 0 (0) |
| 2-axon-16 | 100000 (100000) | 761068 (761068–761068) | 204770 (204353–204770) | 204770 (204770) | 204770 (204770) | 204770 (204770) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-axon-16 | 1693692 | 1693692 | 784.7 MB | 486 | 0 | 0.0 MB | - | 0.6 MB |
| 1-axon-2 | 1849517 | 1849517 | 861.3 MB | 488 | 0 | 0.0 MB | - | 0.3 MB |
| 2-axon-2 | 1866533 | 1866533 | 869.2 MB | 488 | 0 | 0.0 MB | - | 0.3 MB |
| 2-axon-16 | 1680148 | 1680148 | 776.2 MB | 484 | 0 | 0.0 MB | - | 0.6 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-axon-16 | 6.1 s | 300.5 MB | 1317.5 MB |
| 1-axon-2 | 4.8 s | 299.6 MB | 1332.4 MB |
| 2-axon-2 | 4.9 s | 294.9 MB | 1321.4 MB |
| 2-axon-16 | 4.8 s | 296.7 MB | 1323.3 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 1 clients | 4 clients | 16 clients | 64 clients | 256 clients | 1024 clients | 4096 clients | 16384 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-axon-16-e1-sweep | axon-16 | ≥ 3089/s at 4096 (top step) | levelled off | 718/s, p99 3.3 ms | 1689/s, p99 5.1 ms | 2405/s, p99 26.9 ms | 2843/s, p99 76.8 ms | 2993/s, p99 419.8 ms | 3068/s, p99 729.1 ms | 3089/s, p99 1876.0 ms | - |
| 1-axon-16-e3-sweep | axon-16 | 798/s at 256 | peaked below its top step | 255/s, p99 6.9 ms | 579/s, p99 12.7 ms | 760/s, p99 50.2 ms | 796/s, p99 225.3 ms | 798/s, p99 1679.4 ms | 794/s, p99 2916.4 ms | 576/s, 79 errors | - |
| 1-axon-2-e1-sweep | axon-2 | 2994/s at 256 | peaked below its top step | 760/s, p99 3.2 ms | 1772/s, p99 4.9 ms | 2521/s, p99 23.9 ms | 2827/s, p99 88.6 ms | 2994/s, p99 423.9 ms | 2994/s, p99 766.0 ms | 2921/s, p99 2039.8 ms | - |
| 1-axon-2-e3-sweep | axon-2 | 680/s at 4096 | more than 1% failed | 285/s, p99 7.1 ms | 570/s, p99 13.9 ms | 617/s, p99 57.3 ms | 584/s, p99 247.8 ms | 577/s, p99 974.8 ms | 586/s, p99 2457.6 ms | 680/s, p99 6750.2 ms | 107/s, 44785 errors |
| 2-axon-2-e1-sweep | axon-2 | 3071/s at 1024 | peaked below its top step | 742/s, p99 3.5 ms | 1817/s, p99 4.8 ms | 2557/s, p99 22.1 ms | 2949/s, p99 72.7 ms | 3056/s, p99 417.8 ms | 3071/s, p99 712.7 ms | 3035/s, p99 2146.3 ms | - |
| 2-axon-2-e3-sweep | axon-2 | 688/s at 4096 | more than 1% failed | 284/s, p99 6.2 ms | 546/s, p99 14.2 ms | 567/s, p99 62.7 ms | 572/s, p99 255.0 ms | 573/s, p99 974.8 ms | 581/s, p99 2424.8 ms | 688/s, p99 6651.9 ms | 90/s, 46479 errors |
| 2-axon-16-e1-sweep | axon-16 | 3040/s at 1024 | peaked below its top step | 717/s, p99 3.3 ms | 1671/s, p99 5.0 ms | 2386/s, p99 24.4 ms | 2781/s, p99 86.5 ms | 2988/s, p99 423.9 ms | 3040/s, p99 704.5 ms | 2965/s, p99 2097.2 ms | - |
| 2-axon-16-e3-sweep | axon-16 | 797/s at 64 | more than 1% failed | 251/s, p99 7.5 ms | 569/s, p99 13.4 ms | 751/s, p99 48.6 ms | 797/s, p99 225.3 ms | 793/s, p99 1376.3 ms | 773/s, p99 3293.2 ms | 554/s, 417 errors | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-axon-16-e3 | axon-16 | 300.0/s | 300.0/s | 12000 | 0 | 4.1 ms | 8.2 ms | 16.3 ms | n/a | 45.8 ms | 0.72 cores | kafka 57% · postgres 82% | 0.22 of 2 | postgres 0.82 | 7.05 | 263.2 KB | - | - | - |
| 1-axon-2-e3 | axon-2 | 300.0/s | 300.0/s | 12000 | 0 | 3.4 ms | 7.8 ms | 29.1 ms | n/a | 113.2 ms | 0.56 cores | kafka 57% · postgres 84% | 0.21 of 2 | postgres 0.84 | 6.57 | 196.2 KB | - | - | - |
| 2-axon-2-e3 | axon-2 | 300.0/s | 300.0/s | 12000 | 0 | 3.4 ms | 7.7 ms | 19.5 ms | n/a | 50.9 ms | 0.56 cores | kafka 59% · postgres 83% | 0.22 of 2 | postgres 0.83 | 6.58 | 195.9 KB | - | - | - |
| 2-axon-16-e3 | axon-16 | 300.0/s | 300.0/s | 12000 | 0 | 4.0 ms | 8.6 ms | 48.4 ms | n/a | 115.2 ms | 0.73 cores | kafka 57% · postgres 80% | 0.22 of 2 | postgres 0.80 | 7.00 | 263.1 KB | - | - | - |

