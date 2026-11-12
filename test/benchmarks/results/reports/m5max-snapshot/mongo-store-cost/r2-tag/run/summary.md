# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 12, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/wt-final/test/benchmarks/results/e2e/20261111-233541-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7998.3/s | 959800 | 200 | 9.9 ms | 129.0 ms | 198.7 ms | 247.8 ms | 345.1 ms | 307.2 ms |

Errors: {generator-overload=200}

Load generator: 1.58 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.03 | 1483 MB | 905 MB | 155 | 0.540 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.3% | 129.9% | 1004MiB / 31.36GiB |
| wow-e2e-bench-mongo | 167.5% | 262.7% | 2.299GiB / 31.36GiB |

Store operations, mongo (seconds -0.762 to 120.675): {epoch=121.43700003623962, insert=965930, query=426, update=1025160, delete=0, getmore=0, command=5585}
Store operations, kafkaEndOffsets (seconds -29.892 to 122.33): {wow.transfer.account.state=1193292, wow.transfer.commands=0}
