# axon-pg-1-wow-e1-own: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 42, settleSeconds 3, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/1-wow-up.

Open loop at 1507.2/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1507.2/s | 60287 | 0 | 7.6 ms | 148.5 ms | 276.5 ms | 450.6 ms | 503.4 ms | 479.2 ms |

Load generator: 0.44 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.75 | 1338 MB | 817 MB | 12 | 0.322 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.5% | 146.4% | 379.5MiB / 15.61GiB |
| wow-e2e-bench-postgres | 66.7% | 83.3% | 830.1MiB / 15.61GiB |

Store operations, postgres (seconds -0.415 to 40.585): {epoch=40.99999809265137, xact_commit=59922, xact_rollback=0, tup_inserted=60968, tup_updated=134244, tup_deleted=0, tup_fetched=209826, tup_returned=232426, blks_read=26, blks_hit=1723389, wal_records=582183, wal_fpi=140, wal_bytes=79964345, wal_buffers_full=0, wal_write=48833, wal_sync=48833, wal_write_time=970.6420000000016, wal_sync_time=21660.49199999997}

PostgreSQL per second: commits 1461, WAL flushes 1191, WAL flush time 528 ms (max 774 ms), WAL write time 24 ms
