# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-mongo-up.

Open loop at 2882.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2882.0/s | 172920 | 0 | 2.0 ms | 17.0 ms | 133.1 ms | 337.9 ms | 413.2 ms | 405.5 ms |

Load generator: 0.60 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.75 | 1094 MB | 858 MB | 40 | 0.158 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.0% | 117.6% | 2.145GiB / 15.6GiB |
| wow-e2e-bench-mongo | 187.0% | 296.6% | 2.898GiB / 15.6GiB |

Store operations, mongo (seconds -0.547 to 60.714): {epoch=61.26100015640259, insert=174486, query=3988, update=199240, delete=0, getmore=45, command=4125}
Store operations, kafkaEndOffsets (seconds -30.024 to 62.05): {wow.transfer.account.state=237761}
