# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-223313-run-mongo-n1-saturation.

Open loop at 3752.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3752.0/s | 225120 | 0 | 92.2 ms | 243.7 ms | 663.6 ms | 847.9 ms | 1086.7 ms | 913.4 ms |

Load generator: 0.96 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.26 | 1421 MB | 830 MB | 84 | 0.431 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.8% | 164.9% | 2.164GiB / 15.6GiB |
| wow-e2e-bench-mongo | 460.6% | 541.6% | 3.82GiB / 15.6GiB |

Store operations, mongo (seconds -0.629 to 60.638): {epoch=61.26699995994568, insert=683826, query=1137, update=716655, delete=0, getmore=187, command=3431}
Store operations, kafkaEndOffsets (seconds -30.034 to 62.113): {wow.transfer.account.state=1003649}
