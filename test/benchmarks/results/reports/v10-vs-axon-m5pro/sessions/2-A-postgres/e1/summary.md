# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 4, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/2-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.8 ms | 3.2 ms | 20.1 ms | 33.3 ms | 48.3 ms | 27.8 ms |

Load generator: 0.48 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.39 | 1399 MB | 825 MB | 22 | 0.535 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.1% | 153.4% | 420.3MiB / 15.6GiB |
| wow-e2e-bench-postgres | 83.1% | 97.5% | 257.8MiB / 15.6GiB |

Store operations, postgres (seconds -0.064 to 40.938): {epoch=41.00244688987732, xact_commit=168551, xact_rollback=0, tup_inserted=100236, tup_updated=697, tup_deleted=6, tup_fetched=540485, tup_returned=652861, blks_read=0, blks_hit=3190927, wal_records=513013, wal_fpi=2, wal_bytes=64556026, wal_buffers_full=0, wal_write=94632, wal_sync=94632, wal_write_time=597.9969999999998, wal_sync_time=24971.178999999996}

PostgreSQL per second: commits 4111, WAL flushes 2308, WAL flush time 609 ms (max 715 ms), WAL write time 15 ms
