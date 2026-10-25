# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-094252-run-mongo-n1-saturation.

Open loop at 4054.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4054.0/s | 243240 | 0 | 4.9 ms | 36.4 ms | 261.1 ms | 434.2 ms | 520.5 ms | 489.5 ms |

Load generator: 0.96 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.49 | 1069 MB | 850 MB | 43 | 0.243 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.5% | 149.5% | 2.184GiB / 15.6GiB |
| wow-e2e-bench-mongo | 209.6% | 338.2% | 3.007GiB / 15.6GiB |

Store operations, mongo (seconds -0.564 to 60.725): {epoch=61.288999795913696, insert=245467, query=1117, update=276705, delete=0, getmore=148, command=1355}
Store operations, kafkaEndOffsets (seconds -29.727 to 62.104): {wow.transfer.account.state=368676}
