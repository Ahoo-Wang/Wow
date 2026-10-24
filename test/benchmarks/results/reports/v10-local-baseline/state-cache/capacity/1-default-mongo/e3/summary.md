# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 34, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache-mongo/1-default-mongo/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 33.0 ms | 252.9 ms | 395.3 ms | 557.1 ms | 732.2 ms | 651.3 ms |

Load generator: 0.27 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.61 | 1046 MB | 861 MB | 38 | 0.402 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 89.0% | 222.8% | 1.757GiB / 7.748GiB |
| wow-e2e-bench-mongo | 485.7% | 747.1% | 1.531GiB / 7.748GiB |

Store operations, mongo (seconds -0.787 to 60.243): {epoch=61.02999997138977, insert=131653, query=65062, update=160217, delete=0, getmore=0, command=65633}
Store operations, kafkaEndOffsets (seconds -29.977 to 62.389): {wow.transfer.account.state=191637}
