# axon-pg-2-axon-e1-own: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 37, settleSeconds 16, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/2-axon-up.

Open loop at 1646.7/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1646.7/s | 65868 | 0 | 2.0 ms | 145.4 ms | 557.1 ms | 954.4 ms | 1372.1 ms | 1048.6 ms |

Load generator: 0.47 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.78 | 1322 MB | 696 MB | 8 | 0.159 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.2% | 131.3% | 377.3MiB / 15.61GiB |
| wow-e2e-bench-postgres | 62.2% | 92.9% | 675.9MiB / 15.61GiB |

Store operations, postgres (seconds -0.991 to 40.009): {epoch=40.99998688697815, xact_commit=102552, xact_rollback=0, tup_inserted=67276, tup_updated=672, tup_deleted=9, tup_fetched=149012, tup_returned=188627, blks_read=17483, blks_hit=1334901, wal_records=340833, wal_fpi=7790, wal_bytes=74318342, wal_buffers_full=0, wal_write=57756, wal_sync=57756, wal_write_time=835.8680000000022, wal_sync_time=16806.012999999977}

PostgreSQL per second: commits 2501, WAL flushes 1409, WAL flush time 410 ms (max 825 ms), WAL write time 20 ms
