# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-223313-run-mongo-n1-saturation.

Open loop at 12738.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 12476.9/s | 748615 | 15664 | 144.4 ms | 219.1 ms | 544.8 ms | 1105.9 ms | 1366.3 ms | 1220.6 ms |

Errors: {http-429-wow.command.busy=15664}

Load generator: 2.44 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.56 | 1420 MB | 855 MB | 115 | 0.718 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.8% | 178.1% | 2.196GiB / 15.6GiB |
| wow-e2e-bench-mongo | 464.3% | 548.5% | 3.184GiB / 15.6GiB |

Store operations, mongo (seconds -0.447 to 60.831): {epoch=61.27800011634827, insert=754772, query=1105, update=788007, delete=0, getmore=160, command=3479}
Store operations, kafkaEndOffsets (seconds -30.038 to 62.253): {wow.transfer.account.state=1114533}
