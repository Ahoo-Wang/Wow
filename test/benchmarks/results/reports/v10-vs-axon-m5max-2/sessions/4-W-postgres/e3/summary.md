# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/sessions/4-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.4 ms | 10.6 ms | 60.7 ms | 172.0 ms | 186.9 ms | 180.2 ms |

Load generator: 0.14 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.89 | 1280 MB | 750 MB | 9 | 0.024 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 26.6% | 103.4% | 371.4MiB / 31.36GiB |
| wow-e2e-bench-postgres | 48.1% | 77.1% | 726.1MiB / 31.36GiB |

Store operations, postgres (seconds -0.059 to 40.943): {epoch=41.00219106674194, xact_commit=153853, xact_rollback=0, tup_inserted=85043, tup_updated=188749, tup_deleted=7, tup_fetched=247868, tup_returned=292636, blks_read=0, blks_hit=2278599, wal_records=850105, wal_fpi=0, wal_bytes=123100834, wal_buffers_full=0, wal_write=78807, wal_sync=78807, wal_write_time=297.0949999999999, wal_sync_time=37372.744999999995}

PostgreSQL per second: commits 3753, WAL flushes 1922, WAL flush time 912 ms (max 1137 ms), WAL write time 7 ms
