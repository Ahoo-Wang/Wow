# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-mongo-2g-2-up.

Open loop at 1263.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1263.0/s | 75780 | 0 | 5.0 ms | 21.8 ms | 243.7 ms | 378.9 ms | 456.3 ms | 409.6 ms |

Load generator: 0.29 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.65 | 1679 MB | 1684 MB | 22 | 0.257 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.3% | 157.1% | 2.176GiB / 15.6GiB |
| wow-e2e-bench-mongo | 332.6% | 444.8% | 2.307GiB / 15.6GiB |

Store operations, mongo (seconds -0.614 to 60.71): {epoch=61.324000120162964, insert=229656, query=6524, update=260814, delete=0, getmore=0, command=7569}
Store operations, kafkaEndOffsets (seconds -29.899 to 62.1): {wow.transfer.account.state=337528}
