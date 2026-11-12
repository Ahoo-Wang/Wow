# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/wt-final/test/benchmarks/results/e2e/20261112-005804-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7998.0/s | 959756 | 244 | 7.8 ms | 67.1 ms | 132.1 ms | 234.5 ms | 337.4 ms | 294.9 ms |

Errors: {generator-overload=244}

Load generator: 1.63 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.17 | 1489 MB | 891 MB | 158 | 0.534 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.9% | 130.8% | 994.5MiB / 31.36GiB |
| wow-e2e-bench-mongo | 177.6% | 288.4% | 2.261GiB / 31.36GiB |

Store operations, mongo (seconds -0.798 to 120.632): {epoch=121.4300000667572, insert=966170, query=426, update=1028465, delete=0, getmore=0, command=5587}
Store operations, kafkaEndOffsets (seconds -29.705 to 122.39): {wow.transfer.account.state=1192247, wow.transfer.commands=0}
