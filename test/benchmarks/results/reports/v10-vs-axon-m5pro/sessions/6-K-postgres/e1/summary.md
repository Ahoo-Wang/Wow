# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/6-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.7 ms | 1.5 ms | 2.3 ms | 9.2 ms | 18.1 ms | 10.4 ms |

Load generator: 0.44 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.61 | 1217 MB | 753 MB | 14 | 0.039 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.7% | 147.8% | 1.716GiB / 15.6GiB |
| wow-e2e-bench-postgres | 62.8% | 95.6% | 643.2MiB / 15.6GiB |

Store operations, postgres (seconds -0.062 to 40.941): {epoch=41.00345182418823, xact_commit=197568, xact_rollback=0, tup_inserted=101234, tup_updated=223936, tup_deleted=7, tup_fetched=284655, tup_returned=343843, blks_read=0, blks_hit=2522044, wal_records=1032186, wal_fpi=3, wal_bytes=123039676, wal_buffers_full=0, wal_write=172456, wal_sync=172456, wal_write_time=663.5320000000002, wal_sync_time=28642.584000000003}
Store operations, kafkaEndOffsets (seconds -19.884 to 41.785): {wow.transfer.account.state=147634}

PostgreSQL per second: commits 4819, WAL flushes 4206, WAL flush time 699 ms (max 754 ms), WAL write time 16 ms
