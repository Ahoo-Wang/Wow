# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-231946-run-mongo-n1-saturation.

Open loop at 3917.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3678.5/s | 220709 | 14311 | 24.6 ms | 782.3 ms | 3227.6 ms | 4128.8 ms | 4568.8 ms | 4358.1 ms |

Errors: {exception-EOFException=6, http-429-wow.command.busy=14305}

Load generator: 1.18 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.64 | 1244 MB | 838 MB | 76 | 0.674 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 67.4% | 243.1% | 2.159GiB / 15.6GiB |
| wow-e2e-bench-mongo | 563.2% | 1410.3% | 3.388GiB / 15.6GiB |

Store operations, mongo (seconds -0.338 to 60.045): {epoch=60.382999897003174, insert=221934, query=75722, update=250523, delete=0, getmore=129, command=76233}
Store operations, kafkaEndOffsets (seconds -29.65 to 61.645): {wow.transfer.account.state=336714}
