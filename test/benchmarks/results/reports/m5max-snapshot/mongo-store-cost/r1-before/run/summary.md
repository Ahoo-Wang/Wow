# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 10, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/wt-final/test/benchmarks/results/e2e/20261111-232013-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7995.6/s | 959469 | 531 | 9.4 ms | 103.4 ms | 190.5 ms | 246.8 ms | 397.7 ms | 350.2 ms |

Errors: {exception-IOException=18, exception-SocketException=513}

Load generator: 1.63 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.29 | 1529 MB | 969 MB | 164 | 0.895 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.2% | 140.9% | 995.1MiB / 31.36GiB |
| wow-e2e-bench-mongo | 200.0% | 310.5% | 2.187GiB / 31.36GiB |

Store operations, mongo (seconds -0.735 to 120.744): {epoch=121.47899985313416, insert=965385, query=492, update=1033882, delete=0, getmore=0, command=5612}
Store operations, kafkaEndOffsets (seconds -29.89 to 122.419): {wow.transfer.account.state=1192435, wow.transfer.commands=0}
