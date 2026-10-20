# smoke: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo.

Open loop at 50.0/s, uniform over 1000 accounts, 10 s warm-up, 20 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 50.0/s | 1000 | 0 | 8.0 ms | 12.6 ms | 14.5 ms | 25.4 ms | 25.4 ms | 25.4 ms |

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.43 | 1021 MB | 591 MB | 1 | 0.016 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 29.5% | 97.1% | 1.536GiB / 7.748GiB |
| wow-e2e-bench-mongo | 36.0% | 56.1% | 181.8MiB / 7.748GiB |

Store operations in the measured phase: {mongo={insert=987, query=2333, update=7116, delete=0, getmore=0, command=7978}, kafkaEndOffsets={wow.transfer.account.state=962}}
