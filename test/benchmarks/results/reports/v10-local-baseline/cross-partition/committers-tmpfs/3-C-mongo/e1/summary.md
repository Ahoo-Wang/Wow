# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/3-C-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.0 ms | 2.3 ms | 4.0 ms | 9.3 ms | 23.2 ms | 15.8 ms |

Load generator: 0.27 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.97 | 1141 MB | 759 MB | 23 | 0.130 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.7% | 157.3% | 1.714GiB / 7.748GiB |
| wow-e2e-bench-mongo | 110.4% | 186.1% | 2.059GiB / 7.748GiB |

Store operations, mongo (seconds -0.365 to 60.85): {epoch=61.21500015258789, insert=103821, query=327, update=134990, delete=0, getmore=0, command=773}
Store operations, kafkaEndOffsets (seconds -29.833 to 61.946): {wow.transfer.account.state=152975}
