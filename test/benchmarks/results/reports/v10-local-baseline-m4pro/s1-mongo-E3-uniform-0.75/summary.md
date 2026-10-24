# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-mongo-up.

Open loop at 1368.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1327.6/s | 79655 | 2425 | 75.8 ms | 1908.7 ms | 4522.0 ms | 5472.3 ms | 6221.7 ms | 5865.5 ms |

Errors: {http-429-wow.command.busy=2425}

Load generator: 0.34 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.60 | 1248 MB | 838 MB | 87 | 0.669 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.6% | 161.2% | 2.168GiB / 15.6GiB |
| wow-e2e-bench-mongo | 790.2% | 998.4% | 3.897GiB / 15.6GiB |

Store operations, mongo (seconds -0.14 to 60.725): {epoch=60.86500000953674, insert=240600, query=141238, update=250163, delete=0, getmore=2, command=94660}
Store operations, kafkaEndOffsets (seconds -30.051 to 62.44): {wow.transfer.account.state=358889}
