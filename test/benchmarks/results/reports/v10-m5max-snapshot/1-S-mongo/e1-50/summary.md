# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-053401-run-mongo-n1-saturation.

Open loop at 11373.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 11306.9/s | 678413 | 3967 | 16.5 ms | 44.5 ms | 119.3 ms | 385.0 ms | 465.1 ms | 430.1 ms |

Errors: {generator-overload=3967}

Load generator: 2.17 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.76 | 1599 MB | 853 MB | 110 | 0.376 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 44.7% | 145.2% | 1.147GiB / 31.36GiB |
| wow-e2e-bench-mongo | 404.5% | 485.9% | 2.884GiB / 31.36GiB |

Store operations, mongo (seconds -0.674 to 60.606): {epoch=61.28000020980835, insert=686205, query=1147, update=719699, delete=0, getmore=215, command=3680}
Store operations, kafkaEndOffsets (seconds -30.035 to 62.011): {wow.transfer.account.state=1010870, wow.transfer.commands=0}
