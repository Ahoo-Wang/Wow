# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 28, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/1-P-mongo/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 9.6 ms | 33.0 ms | 65.3 ms | 278.5 ms | 350.3 ms | 323.6 ms |

Load generator: 0.20 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.59 | 1113 MB | 821 MB | 39 | 0.237 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 61.0% | 177.6% | 2.006GiB / 7.748GiB |
| wow-e2e-bench-mongo | 323.4% | 450.3% | 1.509GiB / 7.748GiB |

Store operations, mongo (seconds -0.331 to 60.077): {epoch=60.407999992370605, insert=130681, query=64647, update=160295, delete=0, getmore=0, command=65251}
Store operations, kafkaEndOffsets (seconds -30.028 to 62.123): {wow.transfer.account.state=192471}
