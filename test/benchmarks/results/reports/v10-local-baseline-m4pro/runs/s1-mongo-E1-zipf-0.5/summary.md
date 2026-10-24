# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-mongo-up.

Open loop at 1921.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1921.0/s | 115260 | 0 | 1.7 ms | 5.1 ms | 94.2 ms | 297.0 ms | 339.9 ms | 319.5 ms |

Load generator: 0.51 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.26 | 960 MB | 803 MB | 26 | 0.086 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.2% | 127.5% | 2.093GiB / 15.6GiB |
| wow-e2e-bench-mongo | 138.3% | 192.1% | 2.853GiB / 15.6GiB |

Store operations, mongo (seconds -0.608 to 60.631): {epoch=61.23900008201599, insert=116419, query=4018, update=146492, delete=0, getmore=31, command=3864}
Store operations, kafkaEndOffsets (seconds -30.024 to 62.094): {wow.transfer.account.state=164641}
