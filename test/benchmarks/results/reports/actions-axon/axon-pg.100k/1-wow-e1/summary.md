# axon-pg-1-wow-e1: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 42, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/1-wow-up.

Open loop at 1000.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1000.0/s | 40000 | 0 | 17.5 ms | 201.7 ms | 364.5 ms | 501.8 ms | 552.1 ms | 528.4 ms |

Load generator: 0.26 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.56 | 1067 MB | 722 MB | 5 | 0.169 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.5% | 162.5% | 451.5MiB / 15.61GiB |
| wow-e2e-bench-postgres | 47.0% | 91.6% | 584.7MiB / 15.61GiB |

Store operations, postgres (seconds -0.863 to 40.146): {epoch=41.008957862854004, xact_commit=39298, xact_rollback=0, tup_inserted=41208, tup_updated=96720, tup_deleted=7, tup_fetched=128681, tup_returned=163307, blks_read=0, blks_hit=1079044, wal_records=405897, wal_fpi=0, wal_bytes=52505953, wal_buffers_full=0, wal_write=35088, wal_sync=35088, wal_write_time=610.5310000000004, wal_sync_time=24945.978999999992}

PostgreSQL per second: commits 958, WAL flushes 856, WAL flush time 608 ms (max 976 ms), WAL write time 15 ms
