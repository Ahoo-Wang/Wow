# axon-pg-2-wow-e1: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 44, settleSeconds 3, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/2-wow-up.

Open loop at 1000.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1000.0/s | 40000 | 0 | 3.7 ms | 207.9 ms | 376.8 ms | 593.9 ms | 674.9 ms | 643.1 ms |

Load generator: 0.26 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.60 | 1069 MB | 721 MB | 5 | 0.166 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.3% | 166.7% | 488.8MiB / 15.61GiB |
| wow-e2e-bench-postgres | 51.2% | 108.0% | 584.1MiB / 15.61GiB |

Store operations, postgres (seconds -0.989 to 40.011): {epoch=40.99999284744263, xact_commit=50242, xact_rollback=0, tup_inserted=40750, tup_updated=97215, tup_deleted=7, tup_fetched=123141, tup_returned=158196, blks_read=0, blks_hit=1063553, wal_records=409639, wal_fpi=0, wal_bytes=52245461, wal_buffers_full=0, wal_write=45895, wal_sync=45895, wal_write_time=609.2280000000001, wal_sync_time=21479.242}

PostgreSQL per second: commits 1226, WAL flushes 1119, WAL flush time 524 ms (max 970 ms), WAL write time 15 ms
