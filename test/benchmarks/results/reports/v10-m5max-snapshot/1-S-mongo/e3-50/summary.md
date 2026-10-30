# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-053401-run-mongo-n1-saturation.

Open loop at 3378.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3378.0/s | 202680 | 0 | 20.7 ms | 74.2 ms | 256.0 ms | 520.2 ms | 618.2 ms | 573.4 ms |

Load generator: 0.69 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.18 | 1599 MB | 827 MB | 89 | 0.263 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.1% | 115.9% | 1.164GiB / 31.36GiB |
| wow-e2e-bench-mongo | 320.0% | 439.8% | 3.397GiB / 31.36GiB |

Store operations, mongo (seconds -0.755 to 60.505): {epoch=61.25999999046326, insert=615724, query=1084, update=648861, delete=0, getmore=209, command=3619}
Store operations, kafkaEndOffsets (seconds -30.038 to 61.967): {wow.transfer.account.state=908273, wow.transfer.commands=0}
