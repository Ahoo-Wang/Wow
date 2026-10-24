# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-mongo-1g-2-up.

Open loop at 1263.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1263.0/s | 75780 | 0 | 11.5 ms | 42.8 ms | 172.0 ms | 262.1 ms | 317.6 ms | 292.9 ms |

Load generator: 0.30 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.80 | 1009 MB | 836 MB | 62 | 0.273 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.5% | 148.7% | 2.169GiB / 15.6GiB |
| wow-e2e-bench-mongo | 555.8% | 733.2% | 2.376GiB / 15.6GiB |

Store operations, mongo (seconds -0.264 to 60.045): {epoch=60.30900001525879, insert=228338, query=80045, update=258481, delete=0, getmore=0, command=81073}
Store operations, kafkaEndOffsets (seconds -30.041 to 62.123): {wow.transfer.account.state=337479}
