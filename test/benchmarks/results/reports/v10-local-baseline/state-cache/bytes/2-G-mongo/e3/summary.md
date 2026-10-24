# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 58, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/2-G-mongo/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 4.4 ms | 15.5 ms | 34.0 ms | 223.2 ms | 277.1 ms | 262.1 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.30 | 959 MB | 855 MB | 26 | 0.243 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.3% | 165.0% | 1.984GiB / 7.748GiB |
| wow-e2e-bench-mongo | 193.5% | 287.1% | 1.522GiB / 7.748GiB |

Store operations, mongo (seconds -0.502 to 60.776): {epoch=61.27799987792969, insert=131037, query=30463, update=161808, delete=0, getmore=0, command=958}
Store operations, kafkaEndOffsets (seconds -29.995 to 62.154): {wow.transfer.account.state=192540}
