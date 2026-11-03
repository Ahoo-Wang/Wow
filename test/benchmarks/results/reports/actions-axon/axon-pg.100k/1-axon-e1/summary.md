# axon-pg-1-axon-e1: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 37, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/1-axon-up.

Open loop at 1000.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1000.0/s | 40000 | 0 | 1.2 ms | 53.2 ms | 130.6 ms | 346.1 ms | 498.3 ms | 407.6 ms |

Load generator: 0.34 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.67 | 1231 MB | 892 MB | 6 | 0.300 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.7% | 155.1% | 362MiB / 15.61GiB |
| wow-e2e-bench-postgres | 69.4% | 98.3% | 210MiB / 15.61GiB |

Store operations, postgres (seconds -0.996 to 40.004): {epoch=40.99989295005798, xact_commit=93655, xact_rollback=0, tup_inserted=40768, tup_updated=665, tup_deleted=6, tup_fetched=167863, tup_returned=215120, blks_read=0, blks_hit=1348978, wal_records=209439, wal_fpi=0, wal_bytes=26386423, wal_buffers_full=0, wal_write=37993, wal_sync=37993, wal_write_time=479.155, wal_sync_time=10128.606}

PostgreSQL per second: commits 2284, WAL flushes 927, WAL flush time 247 ms (max 683 ms), WAL write time 12 ms
