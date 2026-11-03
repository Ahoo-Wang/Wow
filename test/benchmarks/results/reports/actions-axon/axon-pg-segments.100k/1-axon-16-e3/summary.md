# axon-pg-segments-1-axon-16-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 49, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg-segments.100k/1-axon-16-up.

Open loop at 300.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 300.0/s | 12000 | 0 | 4.1 ms | 8.2 ms | 16.3 ms | 39.4 ms | 52.8 ms | 45.8 ms |

Load generator: 0.22 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.72 | 1020 MB | 685 MB | 5 | 0.153 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.1% | 168.8% | 369.9MiB / 15.61GiB |
| wow-e2e-bench-postgres | 82.4% | 94.0% | 221.6MiB / 15.61GiB |

Store operations, postgres (seconds -0.986 to 40.014): {epoch=40.999871015548706, xact_commit=79747, xact_rollback=0, tup_inserted=49025, tup_updated=24612, tup_deleted=0, tup_fetched=178879, tup_returned=214977, blks_read=0, blks_hit=1336299, wal_records=260380, wal_fpi=0, wal_bytes=37612221, wal_buffers_full=0, wal_write=36337, wal_sync=36337, wal_write_time=573.6580000000004, wal_sync_time=5272.133999999998}

PostgreSQL per second: commits 1945, WAL flushes 886, WAL flush time 129 ms (max 169 ms), WAL write time 14 ms
