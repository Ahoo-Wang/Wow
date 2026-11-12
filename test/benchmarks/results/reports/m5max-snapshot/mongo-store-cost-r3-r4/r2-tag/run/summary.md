# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/wt-final/test/benchmarks/results/e2e/20261112-011715-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7984.4/s | 958128 | 1872 | 8.4 ms | 131.1 ms | 236.5 ms | 299.0 ms | 380.4 ms | 350.2 ms |

Errors: {generator-overload=1872}

Load generator: 1.60 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.09 | 1535 MB | 920 MB | 156 | 0.533 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.7% | 130.3% | 993.8MiB / 31.36GiB |
| wow-e2e-bench-mongo | 173.7% | 288.5% | 2.157GiB / 31.36GiB |

Store operations, mongo (seconds -0.764 to 120.681): {epoch=121.4449999332428, insert=964318, query=426, update=1024544, delete=0, getmore=0, command=5581}
Store operations, kafkaEndOffsets (seconds -29.86 to 122.365): {wow.transfer.account.state=1191519, wow.transfer.commands=0}
