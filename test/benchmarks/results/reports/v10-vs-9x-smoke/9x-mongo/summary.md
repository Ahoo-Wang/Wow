# smoke: 9x E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo.

Open loop at 50.0/s, uniform over 1000 accounts, 10 s warm-up, 20 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 50.0/s | 1000 | 0 | 9.5 ms | 14.0 ms | 16.2 ms | 20.3 ms | 20.3 ms | 20.3 ms |

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.45 | 1066 MB | 679 MB | 1 | 0.003 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.1% | 213.0% | 1.05GiB / 7.748GiB |
| wow-e2e-bench-mongo | 22.9% | 43.1% | 147.3MiB / 7.748GiB |

Store operations in the measured phase: {mongo={insert=986, query=1972, update=986, delete=0, getmore=0, command=116}, kafkaEndOffsets={__consumer_offsets=144, wow.compensation.execution_failed.event=0, wow.transfer.account.command=959, wow.transfer.account.event=960, wow.transfer.account.state=960}}
