# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 32, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/1-A/up.

Open loop at 714.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 714.0/s | 42840 | 0 | 9.2 ms | 27.8 ms | 53.0 ms | 266.2 ms | 320.9 ms | 299.0 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.45 | 1105 MB | 831 MB | 44 | 0.197 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 59.5% | 172.1% | 1.987GiB / 7.748GiB |
| wow-e2e-bench-mongo | 306.8% | 465.3% | 1.507GiB / 7.748GiB |

Store operations, mongo (seconds -0.363 to 60.004): {epoch=60.36699986457825, insert=129300, query=63962, update=158892, delete=0, getmore=0, command=64577}
Store operations, kafkaEndOffsets (seconds -30.026 to 62.164): {wow.transfer.account.state=190667}
