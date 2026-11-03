# axon-pg-1-axon-e3-own: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 37, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/1-axon-up.

Open loop at 332.3/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 332.3/s | 13292 | 0 | 4.0 ms | 131.1 ms | 235.5 ms | 395.3 ms | 430.1 ms | 423.9 ms |

Load generator: 0.18 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.55 | 1314 MB | 713 MB | 6 | 0.146 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 44.4% | 158.6% | 375.5MiB / 15.61GiB |
| wow-e2e-bench-postgres | 65.0% | 84.4% | 675.2MiB / 15.61GiB |

Store operations, postgres (seconds -0.994 to 40.006): {epoch=40.99994993209839, xact_commit=81553, xact_rollback=0, tup_inserted=54855, tup_updated=27442, tup_deleted=0, tup_fetched=177506, tup_returned=222051, blks_read=0, blks_hit=1312862, wal_records=291274, wal_fpi=7635, wal_bytes=68644074, wal_buffers_full=0, wal_write=35409, wal_sync=35409, wal_write_time=607.6110000000008, wal_sync_time=15198.181999999972}

PostgreSQL per second: commits 1989, WAL flushes 864, WAL flush time 371 ms (max 817 ms), WAL write time 15 ms
