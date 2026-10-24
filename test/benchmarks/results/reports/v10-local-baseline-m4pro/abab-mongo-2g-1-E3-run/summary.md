# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-mongo-2g-1-up.

Open loop at 1263.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1263.0/s | 75780 | 0 | 6.7 ms | 264.2 ms | 1736.7 ms | 2228.2 ms | 2398.6 ms | 2326.5 ms |

Load generator: 0.34 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.60 | 1584 MB | 1687 MB | 19 | 0.325 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.5% | 150.4% | 2.236GiB / 15.6GiB |
| wow-e2e-bench-mongo | 326.5% | 628.9% | 2.396GiB / 15.6GiB |

Store operations, mongo (seconds -0.545 to 60.781): {epoch=61.32599997520447, insert=229383, query=5656, update=259300, delete=0, getmore=0, command=6571}
Store operations, kafkaEndOffsets (seconds -30.015 to 62.208): {wow.transfer.account.state=337768}
