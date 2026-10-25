# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-094252-run-mongo-n1-saturation.

Open loop at 12162.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 11758.1/s | 705487 | 24233 | 111.1 ms | 266.2 ms | 827.4 ms | 1089.5 ms | 1291.0 ms | 1122.3 ms |

Errors: {http-429-wow.command.busy=24233}

Load generator: 2.33 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.30 | 1132 MB | 825 MB | 113 | 0.895 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.8% | 172.3% | 2.16GiB / 15.6GiB |
| wow-e2e-bench-mongo | 438.8% | 583.7% | 3.486GiB / 15.6GiB |

Store operations, mongo (seconds -0.518 to 60.976): {epoch=61.49399995803833, insert=712106, query=1030, update=744643, delete=0, getmore=140, command=3301}
Store operations, kafkaEndOffsets (seconds -29.983 to 62.411): {wow.transfer.account.state=1053134}
