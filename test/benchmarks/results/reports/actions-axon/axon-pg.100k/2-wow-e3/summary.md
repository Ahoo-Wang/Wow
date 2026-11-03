# axon-pg-2-wow-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 44, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/2-wow-up.

Open loop at 300.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 300.0/s | 12000 | 0 | 2.2 ms | 38.9 ms | 121.3 ms | 187.4 ms | 216.2 ms | 206.8 ms |

Load generator: 0.20 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.59 | 1081 MB | 732 MB | 4 | 0.133 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.1% | 166.2% | 413MiB / 15.61GiB |
| wow-e2e-bench-postgres | 52.4% | 115.1% | 630.9MiB / 15.61GiB |

Store operations, postgres (seconds -0.978 to 40.022): {epoch=41.000032901763916, xact_commit=67236, xact_rollback=0, tup_inserted=36632, tup_updated=94525, tup_deleted=9, tup_fetched=119111, tup_returned=142524, blks_read=0, blks_hit=1052389, wal_records=390368, wal_fpi=0, wal_bytes=54297773, wal_buffers_full=0, wal_write=63262, wal_sync=63262, wal_write_time=719.623, wal_sync_time=13205.929000000004}

PostgreSQL per second: commits 1640, WAL flushes 1543, WAL flush time 322 ms (max 482 ms), WAL write time 18 ms
