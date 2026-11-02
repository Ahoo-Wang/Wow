# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/heap-8g/sessions/1-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.4 ms | 2.5 ms | 4.6 ms | 14.0 ms | 25.9 ms | 18.0 ms |

Load generator: 0.38 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.08 | 5638 MB | 4937 MB | 2 | 0.013 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 28.5% | 113.3% | 391.5MiB / 31.36GiB |
| wow-e2e-bench-postgres | 57.1% | 81.5% | 631.4MiB / 31.36GiB |

Store operations, postgres (seconds -0.055 to 40.946): {epoch=41.00072193145752, xact_commit=167021, xact_rollback=0, tup_inserted=101072, tup_updated=220750, tup_deleted=7, tup_fetched=323443, tup_returned=382907, blks_read=0, blks_hit=2599370, wal_records=1031478, wal_fpi=3, wal_bytes=123538972, wal_buffers_full=0, wal_write=78916, wal_sync=78916, wal_write_time=330.959, wal_sync_time=37649.339}

PostgreSQL per second: commits 4074, WAL flushes 1925, WAL flush time 918 ms (max 952 ms), WAL write time 8 ms
