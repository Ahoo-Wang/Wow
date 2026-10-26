# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/6-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.6 ms | 1.0 ms | 3.2 ms | 22.7 ms | 59.7 ms | 48.4 ms |

Load generator: 0.43 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.79 | 1104 MB | 728 MB | 16 | 0.061 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.3% | 121.1% | 1.889GiB / 15.6GiB |
| wow-e2e-bench-postgres | 57.4% | 90.3% | 646.3MiB / 15.6GiB |

Store operations, postgres (seconds -0.99 to 40.021): {epoch=41.01032495498657, xact_commit=202818, xact_rollback=0, tup_inserted=102658, tup_updated=227614, tup_deleted=7, tup_fetched=291987, tup_returned=348820, blks_read=0, blks_hit=2558722, wal_records=1047720, wal_fpi=2, wal_bytes=124967393, wal_buffers_full=0, wal_write=195025, wal_sync=195025, wal_write_time=661.6719999999999, wal_sync_time=24997.088999999996}
Store operations, kafkaEndOffsets (seconds -19.835 to 41.84): {wow.transfer.account.state=147759}

PostgreSQL per second: commits 4945, WAL flushes 4755, WAL flush time 610 ms (max 661 ms), WAL write time 16 ms
