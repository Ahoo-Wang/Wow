# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-053401-run-mongo-n1-saturation.

Open loop at 1689.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1689.0/s | 101340 | 0 | 9.9 ms | 20.2 ms | 59.6 ms | 118.3 ms | 151.4 ms | 133.1 ms |

Load generator: 0.30 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.63 | 1599 MB | 838 MB | 50 | 0.132 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.3% | 119.4% | 1.161GiB / 31.36GiB |
| wow-e2e-bench-mongo | 190.9% | 278.5% | 3.217GiB / 31.36GiB |

Store operations, mongo (seconds -0.748 to 60.476): {epoch=61.223999977111816, insert=307777, query=1179, update=339771, delete=0, getmore=200, command=2344}
Store operations, kafkaEndOffsets (seconds -30.035 to 61.949): {wow.transfer.account.state=462896, wow.transfer.commands=0}
