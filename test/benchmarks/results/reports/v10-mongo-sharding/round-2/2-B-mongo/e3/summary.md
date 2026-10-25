# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/sharding-ab/2-B-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 2.2 ms | 5.7 ms | 10.4 ms | 17.8 ms | 30.8 ms | 19.3 ms |

Load generator: 0.16 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.18 | 923 MB | 774 MB | 25 | 0.194 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.8% | 146.1% | 1.96GiB / 7.748GiB |
| wow-e2e-bench-mongo | 121.3% | 218.7% | 2.712GiB / 7.748GiB |

Store operations, mongo (seconds -0.628 to 60.597): {epoch=61.22499990463257, insert=127308, query=325, update=158069, delete=0, getmore=0, command=958}
Store operations, kafkaEndOffsets (seconds -30.031 to 62.033): {wow.transfer.account.state=187453}
