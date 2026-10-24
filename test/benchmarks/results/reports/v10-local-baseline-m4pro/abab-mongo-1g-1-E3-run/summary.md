# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 5, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-mongo-1g-1-up.

Open loop at 1263.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1263.0/s | 75780 | 0 | 11.6 ms | 37.9 ms | 240.6 ms | 485.4 ms | 615.6 ms | 581.6 ms |

Load generator: 0.30 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.00 | 1001 MB | 770 MB | 89 | 0.278 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.3% | 139.1% | 2.167GiB / 15.6GiB |
| wow-e2e-bench-mongo | 545.0% | 668.0% | 2.567GiB / 15.6GiB |

Store operations, mongo (seconds -0.566 to 60.727): {epoch=61.29299998283386, insert=229477, query=79785, update=260124, delete=0, getmore=0, command=80818}
Store operations, kafkaEndOffsets (seconds -30.027 to 62.111): {wow.transfer.account.state=337670}
