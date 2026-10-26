# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-223313-run-mongo-n1-saturation.

Open loop at 4246.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4246.0/s | 254760 | 0 | 5.8 ms | 55.8 ms | 151.6 ms | 528.4 ms | 613.4 ms | 593.9 ms |

Load generator: 1.07 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.76 | 1416 MB | 843 MB | 44 | 0.163 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.0% | 155.4% | 2.202GiB / 15.6GiB |
| wow-e2e-bench-mongo | 208.9% | 305.3% | 2.725GiB / 15.6GiB |

Store operations, mongo (seconds -0.703 to 60.515): {epoch=61.21799993515015, insert=257779, query=1205, update=289101, delete=0, getmore=177, command=1405}
Store operations, kafkaEndOffsets (seconds -29.843 to 62.02): {wow.transfer.account.state=388618}
