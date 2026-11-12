# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/wt-final/test/benchmarks/results/e2e/20261112-010154-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7999.8/s | 959980 | 20 | 10.0 ms | 121.9 ms | 190.5 ms | 234.5 ms | 295.4 ms | 268.3 ms |

Errors: {exception-IOException=1, exception-SocketException=19}

Load generator: 1.53 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.07 | 1556 MB | 962 MB | 163 | 0.845 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.1% | 125.5% | 1.113GiB / 31.36GiB |
| wow-e2e-bench-mongo | 170.6% | 277.6% | 2.206GiB / 31.36GiB |

Store operations, mongo (seconds -0.809 to 120.679): {epoch=121.48800015449524, insert=966385, query=495, update=1032555, delete=0, getmore=0, command=5621}
Store operations, kafkaEndOffsets (seconds -29.882 to 122.443): {wow.transfer.account.state=1193549, wow.transfer.commands=0}
