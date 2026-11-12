# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 11, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/wt-final/test/benchmarks/results/e2e/20261111-233149-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7988.7/s | 958641 | 1359 | 7.4 ms | 53.5 ms | 174.1 ms | 301.1 ms | 397.8 ms | 354.3 ms |

Errors: {generator-overload=1359}

Load generator: 1.63 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.25 | 1547 MB | 960 MB | 168 | 0.904 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.5% | 130.0% | 986.2MiB / 31.36GiB |
| wow-e2e-bench-mongo | 184.7% | 265.6% | 2.17GiB / 31.36GiB |

Store operations, mongo (seconds -0.765 to 120.669): {epoch=121.43400001525879, insert=964806, query=490, update=1036198, delete=0, getmore=0, command=5625}
Store operations, kafkaEndOffsets (seconds -29.89 to 122.379): {wow.transfer.account.state=1191725, wow.transfer.commands=0}
