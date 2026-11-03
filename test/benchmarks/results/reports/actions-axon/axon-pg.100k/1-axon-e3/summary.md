# axon-pg-1-axon-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 37, settleSeconds 3, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/1-axon-up.

Open loop at 300.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 300.0/s | 12000 | 0 | 3.4 ms | 92.2 ms | 177.2 ms | 370.7 ms | 425.2 ms | 415.7 ms |

Load generator: 0.18 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.59 | 1296 MB | 965 MB | 9 | 0.344 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.4% | 163.9% | 364MiB / 15.61GiB |
| wow-e2e-bench-postgres | 70.2% | 86.6% | 249.7MiB / 15.61GiB |

Store operations, postgres (seconds -0.999 to 40.001): {epoch=40.99998998641968, xact_commit=85434, xact_rollback=0, tup_inserted=48940, tup_updated=24526, tup_deleted=6, tup_fetched=182676, tup_returned=396411, blks_read=1, blks_hit=1499397, wal_records=262677, wal_fpi=1, wal_bytes=38499054, wal_buffers_full=0, wal_write=33798, wal_sync=33798, wal_write_time=520.3320000000003, wal_sync_time=10956.594000000005}

PostgreSQL per second: commits 2084, WAL flushes 824, WAL flush time 267 ms (max 704 ms), WAL write time 13 ms
