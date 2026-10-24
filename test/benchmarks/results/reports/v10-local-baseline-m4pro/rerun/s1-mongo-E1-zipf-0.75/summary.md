# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-mongo-up.

Open loop at 2602.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.2/s | 150009 | 6111 | 4.4 ms | 1933.3 ms | 5144.6 ms | 5570.6 ms | 5641.1 ms | 5636.1 ms |

Errors: {http-429-wow.command.busy=6111}

Load generator: 0.52 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.29 | 1216 MB | 851 MB | 60 | 0.201 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 33.6% | 124.1% | 2.137GiB / 15.6GiB |
| wow-e2e-bench-mongo | 149.5% | 277.4% | 3.024GiB / 15.6GiB |

Store operations, mongo (seconds -0.625 to 60.594): {epoch=61.2189998626709, insert=151651, query=4262, update=170971, delete=0, getmore=90, command=4754}
Store operations, kafkaEndOffsets (seconds -30.016 to 62.069): {wow.transfer.account.state=212796}
