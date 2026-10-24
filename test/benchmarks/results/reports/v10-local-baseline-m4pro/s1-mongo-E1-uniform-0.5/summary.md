# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-mongo-up.

Open loop at 4428.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4428.0/s | 265680 | 0 | 7.5 ms | 35.3 ms | 327.7 ms | 843.8 ms | 1000.2 ms | 925.7 ms |

Load generator: 1.11 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.34 | 1089 MB | 792 MB | 77 | 0.362 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.9% | 142.0% | 2.066GiB / 15.6GiB |
| wow-e2e-bench-mongo | 493.9% | 601.4% | 1.983GiB / 15.6GiB |

Store operations, mongo (seconds -0.378 to 60.92): {epoch=61.29800009727478, insert=267344, query=99516, update=298188, delete=0, getmore=0, command=100678}
Store operations, kafkaEndOffsets (seconds -30.033 to 62.226): {wow.transfer.account.state=394136}
