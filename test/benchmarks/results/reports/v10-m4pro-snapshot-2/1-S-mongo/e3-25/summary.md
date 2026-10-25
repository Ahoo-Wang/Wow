# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-094252-run-mongo-n1-saturation.

Open loop at 1445.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1445.0/s | 86700 | 0 | 7.7 ms | 47.1 ms | 327.7 ms | 585.7 ms | 685.8 ms | 651.3 ms |

Load generator: 0.30 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.41 | 1002 MB | 807 MB | 41 | 0.169 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.5% | 141.4% | 2.29GiB / 15.6GiB |
| wow-e2e-bench-mongo | 213.5% | 314.1% | 3.627GiB / 15.6GiB |

Store operations, mongo (seconds -0.487 to 60.788): {epoch=61.27500009536743, insert=262203, query=1134, update=293512, delete=0, getmore=138, command=1648}
Store operations, kafkaEndOffsets (seconds -29.984 to 62.088): {wow.transfer.account.state=396392}
