# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-223313-run-mongo-n1-saturation.

Open loop at 2501.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2501.0/s | 150060 | 0 | 20.4 ms | 46.8 ms | 254.0 ms | 557.1 ms | 736.2 ms | 643.1 ms |

Load generator: 0.63 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.04 | 1421 MB | 866 MB | 60 | 0.232 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.1% | 163.2% | 2.299GiB / 15.6GiB |
| wow-e2e-bench-mongo | 326.5% | 456.3% | 3.615GiB / 15.6GiB |

Store operations, mongo (seconds -0.634 to 60.614): {epoch=61.24799990653992, insert=455058, query=1211, update=487186, delete=0, getmore=168, command=2454}
Store operations, kafkaEndOffsets (seconds -30.034 to 62.028): {wow.transfer.account.state=675831}
