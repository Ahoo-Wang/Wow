# smoke: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres.

Open loop at 50.0/s, uniform over 1000 accounts, 10 s warm-up, 20 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 50.0/s | 1000 | 0 | 6.8 ms | 10.8 ms | 12.3 ms | 14.2 ms | 14.2 ms | 14.2 ms |

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.34 | 722 MB | 341 MB | 0 | 0.000 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 27.2% | 94.9% | 1.405GiB / 7.748GiB |
| wow-e2e-bench-postgres | 11.5% | 16.3% | 83.43MiB / 7.748GiB |

Store operations in the measured phase: {postgres={xact_commit=15525, tup_inserted=1088, tup_updated=8267, tup_deleted=0, tup_fetched=13886, tup_returned=323997, blks_read=23, blks_hit=77351}, kafkaEndOffsets={wow.transfer.account.state=971}}
