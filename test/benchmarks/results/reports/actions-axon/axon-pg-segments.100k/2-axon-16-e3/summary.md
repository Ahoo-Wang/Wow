# axon-pg-segments-2-axon-16-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 53, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg-segments.100k/2-axon-16-up.

Open loop at 300.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 300.0/s | 12000 | 0 | 4.0 ms | 8.6 ms | 48.4 ms | 98.8 ms | 131.4 ms | 115.2 ms |

Load generator: 0.22 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.73 | 1140 MB | 779 MB | 6 | 0.453 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.1% | 162.2% | 363MiB / 15.61GiB |
| wow-e2e-bench-postgres | 80.1% | 111.5% | 221.3MiB / 15.61GiB |

Store operations, postgres (seconds -0.986 to 40.014): {epoch=40.999659061431885, xact_commit=79590, xact_rollback=0, tup_inserted=49278, tup_updated=24708, tup_deleted=6, tup_fetched=152870, tup_returned=577926, blks_read=0, blks_hit=1320834, wal_records=265199, wal_fpi=1, wal_bytes=38407287, wal_buffers_full=0, wal_write=36498, wal_sync=36498, wal_write_time=573.1130000000003, wal_sync_time=5209.767}

PostgreSQL per second: commits 1941, WAL flushes 890, WAL flush time 127 ms (max 155 ms), WAL write time 14 ms
