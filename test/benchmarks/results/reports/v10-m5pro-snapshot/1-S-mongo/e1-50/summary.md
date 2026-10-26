# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-223313-run-mongo-n1-saturation.

Open loop at 8492.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8476.0/s | 508561 | 959 | 47.9 ms | 218.1 ms | 428.0 ms | 651.3 ms | 898.6 ms | 720.9 ms |

Errors: {http-429-wow.command.busy=959}

Load generator: 2.07 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.35 | 1418 MB | 840 MB | 80 | 0.386 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.9% | 175.9% | 2.163GiB / 15.6GiB |
| wow-e2e-bench-mongo | 361.0% | 486.1% | 3.15GiB / 15.6GiB |

Store operations, mongo (seconds -0.525 to 60.718): {epoch=61.24300003051758, insert=513140, query=1147, update=545386, delete=0, getmore=174, command=2465}
Store operations, kafkaEndOffsets (seconds -30.034 to 62.168): {wow.transfer.account.state=760235}
