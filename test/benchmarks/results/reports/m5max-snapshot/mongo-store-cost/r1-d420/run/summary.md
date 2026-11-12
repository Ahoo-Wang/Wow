# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 10, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/wt-final/test/benchmarks/results/e2e/20261111-232405-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8000.0/s | 960000 | 0 | 10.3 ms | 113.2 ms | 184.3 ms | 227.3 ms | 320.1 ms | 297.0 ms |

Load generator: 1.62 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.26 | 1560 MB | 965 MB | 163 | 0.888 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.7% | 152.7% | 1.011GiB / 31.36GiB |
| wow-e2e-bench-mongo | 198.1% | 297.2% | 2.17GiB / 31.36GiB |

Store operations, mongo (seconds -0.739 to 120.751): {epoch=121.49000000953674, insert=965948, query=491, update=1033306, delete=0, getmore=0, command=5624}
Store operations, kafkaEndOffsets (seconds -29.893 to 122.436): {wow.transfer.account.state=1193216, wow.transfer.commands=0}
