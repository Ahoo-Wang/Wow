# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/wt-final/test/benchmarks/results/e2e/20261112-011324-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7993.1/s | 959168 | 832 | 7.3 ms | 43.0 ms | 124.9 ms | 294.9 ms | 439.2 ms | 397.3 ms |

Errors: {generator-overload=832}

Load generator: 1.58 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.24 | 1557 MB | 954 MB | 173 | 0.740 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.0% | 130.9% | 1.121GiB / 31.36GiB |
| wow-e2e-bench-mongo | 185.7% | 319.1% | 2.144GiB / 31.36GiB |

Store operations, mongo (seconds -0.771 to 120.738): {epoch=121.5090000629425, insert=965344, query=492, update=1036694, delete=0, getmore=0, command=5622}
Store operations, kafkaEndOffsets (seconds -29.873 to 122.448): {wow.transfer.account.state=1192329, wow.transfer.commands=0}
