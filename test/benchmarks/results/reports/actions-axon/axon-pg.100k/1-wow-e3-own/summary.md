# axon-pg-1-wow-e3-own: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 42, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/1-wow-up.

Open loop at 368.6/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 368.6/s | 14744 | 0 | 24.2 ms | 290.8 ms | 446.5 ms | 577.5 ms | 631.3 ms | 614.4 ms |

Load generator: 0.19 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.48 | 1339 MB | 734 MB | 9 | 0.140 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.9% | 162.5% | 379.8MiB / 15.61GiB |
| wow-e2e-bench-postgres | 45.8% | 62.0% | 871.2MiB / 15.61GiB |

Store operations, postgres (seconds -0.936 to 40.064): {epoch=41.000099897384644, xact_commit=45590, xact_rollback=0, tup_inserted=45275, tup_updated=106189, tup_deleted=0, tup_fetched=174198, tup_returned=177836, blks_read=12, blks_hit=1315653, wal_records=443203, wal_fpi=15, wal_bytes=66999532, wal_buffers_full=0, wal_write=39575, wal_sync=39575, wal_write_time=794.1500000000015, wal_sync_time=25281.013000000035}

PostgreSQL per second: commits 1112, WAL flushes 965, WAL flush time 617 ms (max 985 ms), WAL write time 19 ms
