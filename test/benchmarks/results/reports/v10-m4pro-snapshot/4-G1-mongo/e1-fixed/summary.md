# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261025-000722-run-mongo-n1-saturation.

Open loop at 7502.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7399.9/s | 443996 | 6124 | 10.1 ms | 104.4 ms | 512.0 ms | 1425.4 ms | 2065.0 ms | 1572.9 ms |

Errors: {http-429-wow.command.busy=6124}

Load generator: 1.78 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.07 | 1224 MB | 845 MB | 71 | 0.414 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.8% | 152.0% | 2.045GiB / 15.6GiB |
| wow-e2e-bench-mongo | 273.3% | 518.3% | 2.334GiB / 15.6GiB |

Store operations, mongo (seconds -0.538 to 60.8): {epoch=61.33800005912781, insert=447948, query=324, update=479468, delete=0, getmore=0, command=2182}
Store operations, kafkaEndOffsets (seconds -29.794 to 62.213): {wow.transfer.account.state=661791}
