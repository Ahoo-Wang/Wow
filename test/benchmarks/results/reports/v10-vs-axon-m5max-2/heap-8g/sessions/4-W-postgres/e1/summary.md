# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/heap-8g/sessions/4-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 2.5 ms | 5.0 ms | 16.3 ms | 29.5 ms | 21.9 ms |

Load generator: 0.34 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.07 | 5660 MB | 4894 MB | 2 | 0.012 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 28.1% | 111.3% | 368.2MiB / 31.36GiB |
| wow-e2e-bench-postgres | 56.4% | 78.3% | 625.2MiB / 31.36GiB |

Store operations, postgres (seconds -0.036 to 40.966): {epoch=41.00199508666992, xact_commit=169541, xact_rollback=0, tup_inserted=101928, tup_updated=222673, tup_deleted=7, tup_fetched=325874, tup_returned=384274, blks_read=0, blks_hit=2617888, wal_records=1039112, wal_fpi=2, wal_bytes=124455196, wal_buffers_full=0, wal_write=80360, wal_sync=80360, wal_write_time=317.91499999999996, wal_sync_time=37973.032}

PostgreSQL per second: commits 4135, WAL flushes 1960, WAL flush time 926 ms (max 959 ms), WAL write time 8 ms
