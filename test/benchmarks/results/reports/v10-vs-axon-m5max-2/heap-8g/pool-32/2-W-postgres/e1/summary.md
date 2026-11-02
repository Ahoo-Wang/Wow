# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/heap-8g/pool-32/2-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.4 ms | 2.5 ms | 5.0 ms | 14.1 ms | 27.5 ms | 19.2 ms |

Load generator: 0.38 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.09 | 5637 MB | 4801 MB | 1 | 0.007 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 28.3% | 111.4% | 398.9MiB / 31.36GiB |
| wow-e2e-bench-postgres | 57.9% | 90.8% | 667.5MiB / 31.36GiB |

Store operations, postgres (seconds -0.053 to 40.948): {epoch=41.00124001502991, xact_commit=167120, xact_rollback=0, tup_inserted=101064, tup_updated=221725, tup_deleted=6, tup_fetched=323063, tup_returned=1161026, blks_read=0, blks_hit=3466381, wal_records=1059941, wal_fpi=0, wal_bytes=124521178, wal_buffers_full=0, wal_write=79104, wal_sync=79104, wal_write_time=322.93300000000005, wal_sync_time=37520.268}

PostgreSQL per second: commits 4076, WAL flushes 1929, WAL flush time 915 ms (max 943 ms), WAL write time 8 ms
