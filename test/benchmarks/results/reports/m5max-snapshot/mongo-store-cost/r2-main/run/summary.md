# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/p1-sweep-edges-b33c1b/test/benchmarks/results/e2e/20261111-155339-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8000.0/s | 960000 | 0 | 6.8 ms | 22.1 ms | 90.6 ms | 147.5 ms | 234.5 ms | 205.8 ms |

Load generator: 1.63 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.34 | 1537 MB | 953 MB | 174 | 0.746 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.3% | 143.6% | 1005MiB / 31.36GiB |
| wow-e2e-bench-mongo | 204.8% | 325.8% | 2.162GiB / 31.36GiB |

Store operations, mongo (seconds -0.773 to 120.699): {epoch=121.47200012207031, insert=966229, query=491, update=1038138, delete=0, getmore=0, command=5603}
Store operations, kafkaEndOffsets (seconds -29.87 to 122.366): {wow.transfer.account.state=1193447, wow.transfer.commands=0}
