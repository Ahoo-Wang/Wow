# axon-pg-1-wow-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 42, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/1-wow-up.

Open loop at 300.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 300.0/s | 12000 | 0 | 16.6 ms | 297.0 ms | 448.5 ms | 626.7 ms | 658.6 ms | 647.2 ms |

Load generator: 0.19 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.42 | 1079 MB | 733 MB | 3 | 0.082 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 44.7% | 164.8% | 366.4MiB / 15.61GiB |
| wow-e2e-bench-postgres | 41.3% | 95.0% | 631.9MiB / 15.61GiB |

Store operations, postgres (seconds -0.916 to 40.084): {epoch=40.999955892562866, xact_commit=41641, xact_rollback=0, tup_inserted=36909, tup_updated=91207, tup_deleted=9, tup_fetched=128050, tup_returned=151370, blks_read=0, blks_hit=1065458, wal_records=374474, wal_fpi=0, wal_bytes=54020849, wal_buffers_full=0, wal_write=36808, wal_sync=36808, wal_write_time=564.4369999999999, wal_sync_time=24808.191999999995}

PostgreSQL per second: commits 1016, WAL flushes 898, WAL flush time 605 ms (max 1049 ms), WAL write time 14 ms
