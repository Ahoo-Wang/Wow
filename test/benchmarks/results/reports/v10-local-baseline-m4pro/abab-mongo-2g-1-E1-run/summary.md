# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 6, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-mongo-2g-1-up.

Open loop at 4729.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4729.0/s | 283740 | 0 | 5.2 ms | 38.1 ms | 268.3 ms | 516.1 ms | 621.2 ms | 573.4 ms |

Load generator: 1.12 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.14 | 1609 MB | 1650 MB | 26 | 0.379 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.2% | 172.2% | 2.179GiB / 15.6GiB |
| wow-e2e-bench-mongo | 398.6% | 540.1% | 2.136GiB / 15.6GiB |

Store operations, mongo (seconds -0.383 to 59.999): {epoch=60.38199996948242, insert=285462, query=3110, update=315829, delete=0, getmore=0, command=4270}
Store operations, kafkaEndOffsets (seconds -29.747 to 62.177): {wow.transfer.account.state=419648}
