# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-mongo-1g-1-up.

Open loop at 4729.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4570.2/s | 274213 | 9527 | 368.6 ms | 1376.3 ms | 1876.0 ms | 2506.8 ms | 3581.5 ms | 2687.0 ms |

Errors: {http-429-wow.command.busy=9527}

Load generator: 0.71 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.30 | 1309 MB | 955 MB | 227 | 2.548 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.5% | 184.3% | 2.155GiB / 15.6GiB |
| wow-e2e-bench-mongo | 847.5% | 1067.9% | 2.259GiB / 15.6GiB |

Store operations, mongo (seconds -0.459 to 60.537): {epoch=60.99600005149841, insert=276321, query=297445, update=191950, delete=0, getmore=30, command=133338}
Store operations, kafkaEndOffsets (seconds -29.673 to 62.497): {wow.transfer.account.state=409342}
