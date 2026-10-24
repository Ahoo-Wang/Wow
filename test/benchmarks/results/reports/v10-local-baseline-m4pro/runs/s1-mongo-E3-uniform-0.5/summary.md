# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-mongo-up.

Open loop at 1608.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1608.0/s | 96479 | 0 | 794.6 ms | 1523.7 ms | 1916.9 ms | 2408.4 ms | 2876.3 ms | 2572.3 ms |

Load generator: 0.37 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.17 | 1258 MB | 906 MB | 179 | 2.294 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 58.3% | 165.6% | 2.155GiB / 15.6GiB |
| wow-e2e-bench-mongo | 800.0% | 983.4% | 3.671GiB / 15.6GiB |

Store operations, mongo (seconds -0.805 to 60.344): {epoch=61.1489999294281, insert=294359, query=422270, update=191293, delete=0, getmore=385, command=88940}
Store operations, kafkaEndOffsets (seconds -30.025 to 62.491): {wow.transfer.account.state=434142}
