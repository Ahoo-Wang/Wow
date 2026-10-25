# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-103004-run-mongo-n1-saturation.

Open loop at 8380.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8380.0/s | 502800 | 0 | 15.2 ms | 81.4 ms | 205.8 ms | 348.2 ms | 471.8 ms | 393.2 ms |

Load generator: 1.97 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.14 | 1184 MB | 864 MB | 77 | 0.434 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.5% | 161.5% | 2.049GiB / 15.6GiB |
| wow-e2e-bench-mongo | 296.0% | 432.9% | 2.355GiB / 15.6GiB |

Store operations, mongo (seconds -0.602 to 60.67): {epoch=61.2720000743866, insert=507805, query=324, update=540074, delete=0, getmore=0, command=2361}
Store operations, kafkaEndOffsets (seconds -29.698 to 62.172): {wow.transfer.account.state=727739}
