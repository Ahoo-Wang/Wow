# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/4-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 0.9 ms | 1.9 ms | 3.1 ms | 10.6 ms | 26.6 ms | 17.7 ms |

Load generator: 0.12 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.83 | 1138 MB | 714 MB | 13 | 0.023 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.8% | 92.5% | 382.2MiB / 15.6GiB |
| wow-e2e-bench-postgres | 42.9% | 81.4% | 746.8MiB / 15.6GiB |

Store operations, postgres (seconds -0.062 to 40.938): {epoch=41.00018095970154, xact_commit=170540, xact_rollback=0, tup_inserted=85372, tup_updated=190247, tup_deleted=7, tup_fetched=236723, tup_returned=282070, blks_read=0, blks_hit=2264423, wal_records=858825, wal_fpi=0, wal_bytes=125911615, wal_buffers_full=0, wal_write=165665, wal_sync=165665, wal_write_time=499.19499999999994, wal_sync_time=20350.68899999999}

PostgreSQL per second: commits 4160, WAL flushes 4041, WAL flush time 496 ms (max 573 ms), WAL write time 12 ms
