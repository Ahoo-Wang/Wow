# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-mongo-up.

Open loop at 6642.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6642.0/s | 398520 | 0 | 40.4 ms | 177.2 ms | 292.9 ms | 409.6 ms | 669.6 ms | 464.9 ms |

Load generator: 1.09 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.22 | 1156 MB | 852 MB | 117 | 0.785 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.4% | 176.8% | 2.083GiB / 15.6GiB |
| wow-e2e-bench-mongo | 816.1% | 960.5% | 2.223GiB / 15.6GiB |

Store operations, mongo (seconds -0.039 to 60.666): {epoch=60.705000162124634, insert=398842, query=175592, update=427843, delete=0, getmore=0, command=169306}
Store operations, kafkaEndOffsets (seconds -30.036 to 62.346): {wow.transfer.account.state=590280}
