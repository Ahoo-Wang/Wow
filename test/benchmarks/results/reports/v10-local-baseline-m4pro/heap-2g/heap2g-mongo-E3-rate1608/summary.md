# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 12, settleSeconds 5, accounts 100000, session /tmp/v10-local-baseline-m4pro/heap2g-runs/heap2g-mongo-up.

Open loop at 1608.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1069.5/s | 64172 | 32307 | 2228.2 ms | 3538.9 ms | 6979.6 ms | 9699.3 ms | 9998.5 ms | 9764.9 ms |

Errors: {http-429-wow.command.busy=25952, http-504-wow.wait.timeout=6355}

Load generator: 0.34 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.69 | 2163 MB | 1754 MB | 46 | 1.313 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.5% | 183.8% | 2.072GiB / 15.6GiB |
| wow-e2e-bench-mongo | 863.5% | 1068.0% | 2.311GiB / 15.6GiB |

Store operations, mongo (seconds -0.465 to 60.88): {epoch=61.34500002861023, insert=202128, query=301144, update=160316, delete=0, getmore=13, command=201432}
Store operations, kafkaEndOffsets (seconds -29.993 to 69.969): {wow.transfer.account.state=338868}
