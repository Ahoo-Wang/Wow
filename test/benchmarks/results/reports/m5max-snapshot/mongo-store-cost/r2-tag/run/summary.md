# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 10, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/p1-sweep-edges-b33c1b/test/benchmarks/results/e2e/20261111-155726-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7994.5/s | 959334 | 666 | 8.4 ms | 107.0 ms | 193.5 ms | 259.1 ms | 386.5 ms | 364.5 ms |

Errors: {generator-overload=666}

Load generator: 1.60 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.11 | 1506 MB | 927 MB | 156 | 0.546 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.8% | 146.4% | 1.045GiB / 31.36GiB |
| wow-e2e-bench-mongo | 179.6% | 326.7% | 2.142GiB / 31.36GiB |

Store operations, mongo (seconds -0.79 to 120.679): {epoch=121.4689998626709, insert=966503, query=428, update=1027269, delete=0, getmore=0, command=5591}
Store operations, kafkaEndOffsets (seconds -29.83 to 122.362): {wow.transfer.account.state=1192342, wow.transfer.commands=0}
