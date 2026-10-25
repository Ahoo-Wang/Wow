# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-103643-run-mongo-n1-saturation.

Open loop at 8380.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8380.0/s | 502800 | 0 | 23.8 ms | 107.0 ms | 217.1 ms | 329.7 ms | 468.8 ms | 391.2 ms |

Load generator: 1.95 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.09 | 1594 MB | 1576 MB | 37 | 0.521 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.8% | 176.4% | 2.049GiB / 15.6GiB |
| wow-e2e-bench-mongo | 324.1% | 558.5% | 2.329GiB / 15.6GiB |

Store operations, mongo (seconds -0.516 to 60.935): {epoch=61.45099997520447, insert=506983, query=324, update=538747, delete=0, getmore=0, command=2344}
Store operations, kafkaEndOffsets (seconds -29.738 to 62.384): {wow.transfer.account.state=738146}
