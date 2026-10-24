# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-mongo-up.

Open loop at 867.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 867.0/s | 52020 | 0 | 2.0 ms | 399.4 ms | 2703.4 ms | 3194.9 ms | 3338.3 ms | 3326.0 ms |

Load generator: 0.29 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.79 | 1093 MB | 844 MB | 15 | 0.108 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 29.9% | 108.3% | 2.123GiB / 15.6GiB |
| wow-e2e-bench-mongo | 67.9% | 176.3% | 2.612GiB / 15.6GiB |

Store operations, mongo (seconds -0.695 to 60.497): {epoch=61.19200015068054, insert=52614, query=3425, update=81011, delete=0, getmore=29, command=3688}
Store operations, kafkaEndOffsets (seconds -30.011 to 62.245): {wow.transfer.account.state=88681}
