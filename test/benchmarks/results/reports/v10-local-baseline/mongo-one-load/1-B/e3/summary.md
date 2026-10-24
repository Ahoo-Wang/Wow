# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 39, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/1-B/up.

Open loop at 714.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 714.0/s | 42840 | 0 | 22.8 ms | 100.4 ms | 153.6 ms | 220.2 ms | 357.0 ms | 268.3 ms |

Load generator: 0.24 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.54 | 1106 MB | 840 MB | 43 | 0.295 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 77.2% | 188.9% | 1.986GiB / 7.748GiB |
| wow-e2e-bench-mongo | 437.9% | 658.6% | 1.665GiB / 7.748GiB |

Store operations, mongo (seconds -0.069 to 60.681): {epoch=60.75, insert=128695, query=322, update=157796, delete=0, getmore=0, command=64220}
Store operations, kafkaEndOffsets (seconds -30.011 to 62.385): {wow.transfer.account.state=189905}
