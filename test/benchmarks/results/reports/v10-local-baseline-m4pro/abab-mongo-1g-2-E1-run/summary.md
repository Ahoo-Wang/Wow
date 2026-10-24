# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-mongo-1g-2-up.

Open loop at 4729.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4729.0/s | 283740 | 0 | 146.4 ms | 553.0 ms | 790.5 ms | 1073.2 ms | 1764.0 ms | 1138.7 ms |

Load generator: 0.79 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.82 | 1204 MB | 891 MB | 119 | 0.894 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.2% | 167.3% | 2.157GiB / 15.6GiB |
| wow-e2e-bench-mongo | 868.5% | 1008.2% | 2.061GiB / 15.6GiB |

Store operations, mongo (seconds -0.002 to 60.938): {epoch=60.94000005722046, insert=283745, query=216976, update=280947, delete=0, getmore=0, command=142143}
Store operations, kafkaEndOffsets (seconds -29.661 to 62.48): {wow.transfer.account.state=419350}
