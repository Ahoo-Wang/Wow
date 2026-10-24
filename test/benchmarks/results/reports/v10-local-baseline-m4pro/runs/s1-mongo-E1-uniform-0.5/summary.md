# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-mongo-up.

Open loop at 6277.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6128.9/s | 367733 | 8887 | 23.7 ms | 86.0 ms | 1630.2 ms | 2080.8 ms | 2270.4 ms | 2195.5 ms |

Errors: {http-429-wow.command.busy=8887}

Load generator: 1.27 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.30 | 1114 MB | 856 MB | 83 | 0.670 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.5% | 178.9% | 2.038GiB / 15.6GiB |
| wow-e2e-bench-mongo | 575.9% | 682.2% | 2.038GiB / 15.6GiB |

Store operations, mongo (seconds -0.049 to 60.512): {epoch=60.56099987030029, insert=368022, query=127846, update=397227, delete=0, getmore=0, command=74983}
Store operations, kafkaEndOffsets (seconds -30.038 to 62.381): {wow.transfer.account.state=542579}
