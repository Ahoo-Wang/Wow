# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 12, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/heap2g-runs/heap2g-mongo-up.

Open loop at 6277.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6277.0/s | 376620 | 0 | 10.7 ms | 38.1 ms | 123.9 ms | 432.1 ms | 554.8 ms | 493.6 ms |

Load generator: 1.50 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.38 | 1764 MB | 1469 MB | 36 | 0.328 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.4% | 153.1% | 2.023GiB / 15.6GiB |
| wow-e2e-bench-mongo | 412.3% | 525.7% | 1.837GiB / 15.6GiB |

Store operations, mongo (seconds -0.472 to 60.807): {epoch=61.27899980545044, insert=379540, query=131650, update=410808, delete=0, getmore=0, command=1857}
Store operations, kafkaEndOffsets (seconds -29.775 to 62.149): {wow.transfer.account.state=556650}
