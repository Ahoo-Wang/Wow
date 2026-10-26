# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-232651-run-mongo-n1-saturation.

Open loop at 7017.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7017.0/s | 421020 | 0 | 10.2 ms | 27.0 ms | 72.7 ms | 266.2 ms | 364.6 ms | 323.6 ms |

Load generator: 1.76 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.30 | 2067 MB | 1537 MB | 31 | 0.170 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.9% | 169.7% | 2.127GiB / 15.6GiB |
| wow-e2e-bench-mongo | 312.3% | 461.6% | 2.3GiB / 15.6GiB |

Store operations, mongo (seconds -0.726 to 60.53): {epoch=61.25600004196167, insert=426189, query=325, update=458246, delete=0, getmore=0, command=2036}
Store operations, kafkaEndOffsets (seconds -29.847 to 62.069): {wow.transfer.account.state=625328}
