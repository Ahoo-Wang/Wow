# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-mongo-up.

Open loop at 912.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 912.0/s | 54720 | 0 | 10.1 ms | 63.2 ms | 197.6 ms | 299.0 ms | 368.0 ms | 321.5 ms |

Load generator: 0.23 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.60 | 990 MB | 757 MB | 75 | 0.296 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.5% | 148.5% | 2.161GiB / 15.6GiB |
| wow-e2e-bench-mongo | 492.1% | 742.0% | 3.737GiB / 15.6GiB |

Store operations, mongo (seconds -0.765 to 60.584): {epoch=61.348999977111816, insert=166253, query=63313, update=196647, delete=0, getmore=0, command=64517}
Store operations, kafkaEndOffsets (seconds -30.027 to 62.115): {wow.transfer.account.state=243012}
