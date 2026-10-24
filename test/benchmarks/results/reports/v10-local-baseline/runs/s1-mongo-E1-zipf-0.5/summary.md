# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 36, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-mongo-up.

Open loop at 1540.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1540.0/s | 92400 | 0 | 1.3 ms | 3.7 ms | 9.9 ms | 59.4 ms | 136.6 ms | 95.7 ms |

Load generator: 0.27 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.06 | 1010 MB | 849 MB | 24 | 0.131 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.9% | 138.7% | 1.842GiB / 7.748GiB |
| wow-e2e-bench-mongo | 102.5% | 186.6% | 2.505GiB / 7.748GiB |

Store operations, mongo (seconds -0.558 to 60.638): {epoch=61.19600009918213, insert=93250, query=6970, update=124056, delete=0, getmore=29, command=7096}
Store operations, kafkaEndOffsets (seconds -30.033 to 62.118): {wow.transfer.account.state=135361}
