# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 7, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-231946-run-mongo-n1-saturation.

Open loop at 792.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 792.0/s | 47520 | 0 | 9.3 ms | 37.4 ms | 205.8 ms | 317.4 ms | 375.5 ms | 358.4 ms |

Load generator: 0.20 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.33 | 1251 MB | 829 MB | 40 | 0.182 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.3% | 166.1% | 2.169GiB / 15.6GiB |
| wow-e2e-bench-mongo | 295.6% | 656.4% | 4.068GiB / 15.6GiB |

Store operations, mongo (seconds -0.7 to 60.57): {epoch=61.269999980926514, insert=144251, query=46772, update=175096, delete=0, getmore=120, command=46792}
Store operations, kafkaEndOffsets (seconds -30.028 to 62.023): {wow.transfer.account.state=221134}
