# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 11, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/p1-sweep-edges-b33c1b/test/benchmarks/results/e2e/20261111-153834-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8000.0/s | 960000 | 0 | 7.4 ms | 40.7 ms | 113.7 ms | 149.5 ms | 221.8 ms | 198.7 ms |

Load generator: 1.66 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.29 | 1534 MB | 971 MB | 172 | 0.759 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.1% | 152.5% | 1.027GiB / 31.36GiB |
| wow-e2e-bench-mongo | 185.0% | 284.2% | 2.159GiB / 31.36GiB |

Store operations, mongo (seconds -0.751 to 120.711): {epoch=121.46200013160706, insert=966648, query=490, update=1037511, delete=0, getmore=0, command=5601}
Store operations, kafkaEndOffsets (seconds -29.863 to 122.387): {wow.transfer.account.state=1193340, wow.transfer.commands=0}
