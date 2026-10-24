# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-mongo-up.

Open loop at 3138.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2986.8/s | 179207 | 9073 | 3.7 ms | 66.6 ms | 3751.9 ms | 4259.8 ms | 4375.0 ms | 4358.1 ms |

Errors: {http-429-wow.command.busy=9073}

Load generator: 0.82 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.76 | 1130 MB | 836 MB | 44 | 0.288 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.7% | 157.8% | 2.151GiB / 15.6GiB |
| wow-e2e-bench-mongo | 243.6% | 419.2% | 1.971GiB / 15.6GiB |

Store operations, mongo (seconds -0.542 to 60.753): {epoch=61.295000076293945, insert=180859, query=62915, update=210673, delete=0, getmore=0, command=25238}
Store operations, kafkaEndOffsets (seconds -29.77 to 62.094): {wow.transfer.account.state=269866}
