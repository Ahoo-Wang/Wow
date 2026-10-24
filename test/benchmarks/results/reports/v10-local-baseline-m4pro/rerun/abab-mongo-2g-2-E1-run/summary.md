# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 8, settleSeconds 6, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-mongo-2g-2-up.

Open loop at 4729.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4582.3/s | 274935 | 8805 | 7.0 ms | 121.3 ms | 2359.3 ms | 2801.7 ms | 3060.1 ms | 2900.0 ms |

Errors: {http-429-wow.command.busy=8805}

Load generator: 1.12 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.88 | 1760 MB | 1667 MB | 25 | 0.346 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.8% | 154.7% | 2.176GiB / 15.6GiB |
| wow-e2e-bench-mongo | 378.8% | 549.2% | 2.104GiB / 15.6GiB |

Store operations, mongo (seconds -0.513 to 60.85): {epoch=61.36299991607666, insert=277254, query=4150, update=307571, delete=0, getmore=0, command=5395}
Store operations, kafkaEndOffsets (seconds -29.662 to 62.267): {wow.transfer.account.state=410984}
