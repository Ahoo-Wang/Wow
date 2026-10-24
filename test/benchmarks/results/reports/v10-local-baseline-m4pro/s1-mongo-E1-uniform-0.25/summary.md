# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 5, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-mongo-up.

Open loop at 2214.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2214.0/s | 132840 | 0 | 2.2 ms | 6.3 ms | 95.7 ms | 242.7 ms | 299.3 ms | 284.7 ms |

Load generator: 0.41 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.50 | 1059 MB | 819 MB | 40 | 0.154 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.8% | 116.6% | 2.052GiB / 15.6GiB |
| wow-e2e-bench-mongo | 228.9% | 310.6% | 1.754GiB / 15.6GiB |

Store operations, mongo (seconds -0.527 to 60.712): {epoch=61.23900008201599, insert=133991, query=46259, update=164778, delete=0, getmore=0, command=46824}
Store operations, kafkaEndOffsets (seconds -29.74 to 62.019): {wow.transfer.account.state=196749}
