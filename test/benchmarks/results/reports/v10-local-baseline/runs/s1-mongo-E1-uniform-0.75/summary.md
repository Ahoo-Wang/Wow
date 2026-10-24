# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 36, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-mongo-up.

Open loop at 2584.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2584.0/s | 155040 | 0 | 40.4 ms | 129.0 ms | 184.3 ms | 248.8 ms | 356.3 ms | 256.0 ms |

Load generator: 0.47 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.68 | 1108 MB | 848 MB | 72 | 0.601 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 96.0% | 213.5% | 1.957GiB / 7.748GiB |
| wow-e2e-bench-mongo | 670.9% | 824.8% | 2.27GiB / 7.748GiB |

Store operations, mongo (seconds -0.495 to 59.989): {epoch=60.48399996757507, insert=156280, query=115383, update=179693, delete=0, getmore=0, command=116102}
Store operations, kafkaEndOffsets (seconds -30.027 to 62.411): {wow.transfer.account.state=227967}
