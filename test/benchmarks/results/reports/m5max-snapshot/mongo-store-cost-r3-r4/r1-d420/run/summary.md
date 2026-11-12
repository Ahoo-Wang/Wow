# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 10, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/wt-final/test/benchmarks/results/e2e/20261112-010545-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7998.1/s | 959769 | 231 | 8.9 ms | 105.5 ms | 193.5 ms | 258.0 ms | 340.3 ms | 309.2 ms |

Errors: {generator-overload=231}

Load generator: 1.54 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.15 | 1538 MB | 962 MB | 170 | 0.737 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.0% | 122.8% | 1.113GiB / 31.36GiB |
| wow-e2e-bench-mongo | 168.4% | 245.5% | 2.195GiB / 31.36GiB |

Store operations, mongo (seconds -0.771 to 120.712): {epoch=121.48300004005432, insert=965945, query=491, update=1033849, delete=0, getmore=0, command=5636}
Store operations, kafkaEndOffsets (seconds -29.873 to 122.507): {wow.transfer.account.state=1193112, wow.transfer.commands=0}
