# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 27, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/2-D2-mongo/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 9.0 ms | 29.1 ms | 57.6 ms | 160.8 ms | 238.6 ms | 220.2 ms |

Load generator: 0.19 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.37 | 1075 MB | 828 MB | 42 | 0.189 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 61.8% | 184.1% | 2.099GiB / 7.748GiB |
| wow-e2e-bench-mongo | 324.0% | 438.2% | 1.716GiB / 7.748GiB |

Store operations, mongo (seconds -0.252 to 60.146): {epoch=60.39800000190735, insert=130504, query=64558, update=160616, delete=0, getmore=0, command=65171}
Store operations, kafkaEndOffsets (seconds -30.016 to 62.096): {wow.transfer.account.state=192386}
