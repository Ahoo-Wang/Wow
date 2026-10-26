# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/4-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.1 ms | 8.4 ms | 313.3 ms | 536.6 ms | 578.1 ms | 557.1 ms |

Load generator: 0.15 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.95 | 1171 MB | 684 MB | 8 | 0.024 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 27.0% | 110.5% | 380.2MiB / 15.6GiB |
| wow-e2e-bench-postgres | 43.4% | 78.8% | 751MiB / 15.6GiB |

Store operations, postgres (seconds -0.046 to 40.954): {epoch=41.000473976135254, xact_commit=163635, xact_rollback=0, tup_inserted=85740, tup_updated=189424, tup_deleted=7, tup_fetched=240189, tup_returned=286278, blks_read=0, blks_hit=2275697, wal_records=857246, wal_fpi=0, wal_bytes=126274180, wal_buffers_full=0, wal_write=159400, wal_sync=159400, wal_write_time=517.191, wal_sync_time=18343.04699999999}

PostgreSQL per second: commits 3991, WAL flushes 3888, WAL flush time 447 ms (max 736 ms), WAL write time 13 ms
