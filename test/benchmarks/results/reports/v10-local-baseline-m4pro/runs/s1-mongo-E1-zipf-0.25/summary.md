# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-mongo-up.

Open loop at 961.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 961.0/s | 57660 | 0 | 1.4 ms | 2.9 ms | 8.4 ms | 90.1 ms | 126.2 ms | 120.8 ms |

Load generator: 0.16 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.68 | 886 MB | 754 MB | 14 | 0.043 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.8% | 120.3% | 2.112GiB / 15.6GiB |
| wow-e2e-bench-mongo | 82.5% | 108.9% | 2.508GiB / 15.6GiB |

Store operations, mongo (seconds -0.552 to 60.656): {epoch=61.20799994468689, insert=58187, query=3087, update=88629, delete=0, getmore=24, command=2774}
Store operations, kafkaEndOffsets (seconds -29.971 to 62.054): {wow.transfer.account.state=94710}
