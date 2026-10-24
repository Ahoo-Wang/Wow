# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-mongo-up.

Open loop at 1735.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1735.0/s | 104100 | 0 | 1.3 ms | 3.5 ms | 57.1 ms | 305.2 ms | 490.4 ms | 426.0 ms |

Load generator: 0.27 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.11 | 1005 MB | 785 MB | 24 | 0.057 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 33.5% | 127.5% | 2.127GiB / 15.6GiB |
| wow-e2e-bench-mongo | 121.2% | 168.2% | 2.808GiB / 15.6GiB |

Store operations, mongo (seconds -0.682 to 60.526): {epoch=61.20799994468689, insert=105278, query=4654, update=135982, delete=0, getmore=57, command=4832}
Store operations, kafkaEndOffsets (seconds -30.03 to 61.987): {wow.transfer.account.state=150945}
