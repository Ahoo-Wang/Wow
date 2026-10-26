# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/1-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.6 ms | 0.9 ms | 2.1 ms | 9.6 ms | 16.4 ms | 8.9 ms |

Load generator: 0.38 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.39 | 1177 MB | 723 MB | 12 | 0.040 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 27.9% | 134.0% | 473.1MiB / 15.6GiB |
| wow-e2e-bench-postgres | 50.3% | 72.5% | 688.3MiB / 15.6GiB |

Store operations, postgres (seconds -0.999 to 40.001): {epoch=41.00057506561279, xact_commit=203689, xact_rollback=0, tup_inserted=102543, tup_updated=223868, tup_deleted=7, tup_fetched=290110, tup_returned=345035, blks_read=0, blks_hit=2539556, wal_records=1039801, wal_fpi=3, wal_bytes=124337466, wal_buffers_full=0, wal_write=199586, wal_sync=199586, wal_write_time=575.632, wal_sync_time=23306.907999999996}

PostgreSQL per second: commits 4968, WAL flushes 4868, WAL flush time 568 ms (max 625 ms), WAL write time 14 ms
