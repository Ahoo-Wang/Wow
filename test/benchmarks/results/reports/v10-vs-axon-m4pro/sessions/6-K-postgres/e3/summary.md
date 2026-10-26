# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/6-K-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.1 ms | 1.8 ms | 85.5 ms | 421.9 ms | 459.0 ms | 450.6 ms |

Load generator: 0.14 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.29 | 1030 MB | 644 MB | 10 | 0.031 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 32.8% | 116.5% | 1.831GiB / 15.6GiB |
| wow-e2e-bench-postgres | 46.3% | 81.3% | 758.3MiB / 15.6GiB |

Store operations, postgres (seconds -0.051 to 40.95): {epoch=41.00076484680176, xact_commit=165612, xact_rollback=0, tup_inserted=84266, tup_updated=189358, tup_deleted=7, tup_fetched=235966, tup_returned=281148, blks_read=0, blks_hit=2245930, wal_records=850576, wal_fpi=0, wal_bytes=124667990, wal_buffers_full=0, wal_write=162103, wal_sync=162103, wal_write_time=551.154, wal_sync_time=18309.763999999996}
Store operations, kafkaEndOffsets (seconds -20.027 to 41.823): {wow.transfer.account.state=124771}

PostgreSQL per second: commits 4039, WAL flushes 3954, WAL flush time 447 ms (max 677 ms), WAL write time 13 ms
