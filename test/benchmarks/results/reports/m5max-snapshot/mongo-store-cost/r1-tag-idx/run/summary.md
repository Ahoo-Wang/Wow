# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/p1-sweep-edges-b33c1b/test/benchmarks/results/e2e/20261111-154221-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8000.0/s | 960000 | 0 | 7.6 ms | 72.7 ms | 184.3 ms | 235.5 ms | 306.2 ms | 268.3 ms |

Load generator: 1.63 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.24 | 1528 MB | 905 MB | 158 | 0.549 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.6% | 138.2% | 1006MiB / 31.36GiB |
| wow-e2e-bench-mongo | 195.4% | 318.2% | 2.147GiB / 31.36GiB |

Store operations, mongo (seconds -0.776 to 120.663): {epoch=121.43899989128113, insert=966224, query=429, update=1029520, delete=0, getmore=0, command=5588}
Store operations, kafkaEndOffsets (seconds -29.881 to 122.38): {wow.transfer.account.state=1193512, wow.transfer.commands=0}
