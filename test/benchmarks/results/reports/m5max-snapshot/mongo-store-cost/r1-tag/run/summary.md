# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 10, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/wt-final/test/benchmarks/results/e2e/20261111-231622-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8000.0/s | 960000 | 0 | 7.8 ms | 26.5 ms | 111.1 ms | 171.0 ms | 245.2 ms | 218.1 ms |

Load generator: 1.68 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.34 | 1508 MB | 855 MB | 158 | 0.545 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.8% | 164.1% | 1.062GiB / 31.36GiB |
| wow-e2e-bench-mongo | 207.3% | 357.2% | 2.176GiB / 31.36GiB |

Store operations, mongo (seconds -0.745 to 120.753): {epoch=121.49799990653992, insert=965973, query=437, update=1029792, delete=0, getmore=0, command=5576}
Store operations, kafkaEndOffsets (seconds -29.748 to 122.422): {wow.transfer.account.state=1192152, wow.transfer.commands=0}
