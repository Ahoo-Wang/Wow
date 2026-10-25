# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers/5-B-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 6.6 ms | 20.9 ms | 42.5 ms | 154.6 ms | 210.9 ms | 197.6 ms |

Load generator: 0.20 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.12 | 938 MB | 734 MB | 35 | 0.241 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.0% | 160.6% | 1.916GiB / 7.748GiB |
| wow-e2e-bench-mongo | 139.2% | 252.8% | 1.614GiB / 7.748GiB |

Store operations, mongo (seconds -0.595 to 60.67): {epoch=61.264999866485596, insert=127243, query=324, update=158004, delete=0, getmore=0, command=949}
Store operations, kafkaEndOffsets (seconds -30.026 to 62.136): {wow.transfer.account.state=187389}
