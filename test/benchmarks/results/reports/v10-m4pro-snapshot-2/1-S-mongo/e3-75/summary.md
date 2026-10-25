# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-094252-run-mongo-n1-saturation.

Open loop at 4336.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4097.9/s | 245873 | 14287 | 102.4 ms | 481.3 ms | 2670.6 ms | 3014.7 ms | 3418.1 ms | 3113.0 ms |

Errors: {http-429-wow.command.busy=14287}

Load generator: 0.92 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.06 | 1100 MB | 825 MB | 100 | 0.669 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.0% | 164.8% | 2.133GiB / 15.6GiB |
| wow-e2e-bench-mongo | 464.0% | 584.3% | 4.29GiB / 15.6GiB |

Store operations, mongo (seconds -0.465 to 60.013): {epoch=60.4779999256134, insert=744320, query=1296, update=775139, delete=0, getmore=158, command=3805}
Store operations, kafkaEndOffsets (seconds -30.005 to 62.355): {wow.transfer.account.state=1113409}
