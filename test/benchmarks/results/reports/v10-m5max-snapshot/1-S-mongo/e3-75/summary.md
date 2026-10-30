# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-053401-run-mongo-n1-saturation.

Open loop at 5066.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4999.1/s | 299947 | 4013 | 78.3 ms | 172.0 ms | 434.2 ms | 1056.8 ms | 1169.1 ms | 1105.9 ms |

Errors: {generator-overload=4013}

Load generator: 1.00 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.66 | 1599 MB | 814 MB | 127 | 0.452 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.9% | 138.6% | 1.15GiB / 31.36GiB |
| wow-e2e-bench-mongo | 462.9% | 550.9% | 3.594GiB / 31.36GiB |

Store operations, mongo (seconds -0.697 to 60.565): {epoch=61.26200008392334, insert=911304, query=2690, update=945587, delete=0, getmore=528, command=4926}
Store operations, kafkaEndOffsets (seconds -30.035 to 61.978): {wow.transfer.account.state=1337143, wow.transfer.commands=0}
