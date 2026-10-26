# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-223313-run-mongo-n1-saturation.

Open loop at 1251.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1251.0/s | 75060 | 0 | 7.6 ms | 17.8 ms | 252.9 ms | 434.2 ms | 509.7 ms | 477.2 ms |

Load generator: 0.30 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.57 | 1420 MB | 800 MB | 35 | 0.122 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.1% | 144.3% | 2.159GiB / 15.6GiB |
| wow-e2e-bench-mongo | 192.9% | 284.0% | 3.424GiB / 15.6GiB |

Store operations, mongo (seconds -0.651 to 60.555): {epoch=61.20599985122681, insert=227625, query=1196, update=259298, delete=0, getmore=155, command=1508}
Store operations, kafkaEndOffsets (seconds -30.032 to 61.946): {wow.transfer.account.state=346884}
