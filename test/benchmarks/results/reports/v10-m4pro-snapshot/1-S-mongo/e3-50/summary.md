# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-231946-run-mongo-n1-saturation.

Open loop at 1584.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1584.0/s | 95040 | 0 | 32.1 ms | 335.9 ms | 569.3 ms | 852.0 ms | 1004.6 ms | 909.3 ms |

Load generator: 0.37 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.70 | 1060 MB | 853 MB | 68 | 0.402 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.0% | 172.8% | 2.168GiB / 15.6GiB |
| wow-e2e-bench-mongo | 584.0% | 795.9% | 4.274GiB / 15.6GiB |

Store operations, mongo (seconds -0.393 to 60.033): {epoch=60.425999879837036, insert=285400, query=85833, update=314992, delete=0, getmore=134, command=86544}
Store operations, kafkaEndOffsets (seconds -30.019 to 62.217): {wow.transfer.account.state=430263}
