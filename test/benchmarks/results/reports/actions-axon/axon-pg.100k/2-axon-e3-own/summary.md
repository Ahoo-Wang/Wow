# axon-pg-2-axon-e3-own: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 37, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/2-axon-up.

Open loop at 205.2/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 205.2/s | 8208 | 0 | 3.1 ms | 69.6 ms | 152.6 ms | 201.7 ms | 216.2 ms | 214.0 ms |

Load generator: 0.19 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.46 | 1322 MB | 714 MB | 4 | 0.095 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.0% | 167.0% | 377.4MiB / 15.61GiB |
| wow-e2e-bench-postgres | 62.8% | 76.5% | 680.3MiB / 15.61GiB |

Store operations, postgres (seconds -0.984 to 40.015): {epoch=40.99962496757507, xact_commit=76679, xact_rollback=0, tup_inserted=33998, tup_updated=17011, tup_deleted=0, tup_fetched=169209, tup_returned=196987, blks_read=2, blks_hit=1321066, wal_records=180512, wal_fpi=56, wal_bytes=26362514, wal_buffers_full=0, wal_write=23805, wal_sync=23805, wal_write_time=323.40200000000186, wal_sync_time=9979.466000000015}

PostgreSQL per second: commits 1870, WAL flushes 581, WAL flush time 243 ms (max 572 ms), WAL write time 8 ms
