# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/4-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 2.5 ms | 4.5 ms | 12.5 ms | 23.6 ms | 15.7 ms |

Load generator: 0.34 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.22 | 1491 MB | 966 MB | 37 | 0.065 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 27.5% | 105.3% | 371.7MiB / 31.36GiB |
| wow-e2e-bench-postgres | 57.9% | 82.9% | 630.3MiB / 31.36GiB |

Store operations, postgres (seconds -0.057 to 40.95): {epoch=41.00706505775452, xact_commit=168756, xact_rollback=0, tup_inserted=101554, tup_updated=223699, tup_deleted=7, tup_fetched=330061, tup_returned=387534, blks_read=0, blks_hit=2625031, wal_records=1040779, wal_fpi=2, wal_bytes=124158252, wal_buffers_full=0, wal_write=78567, wal_sync=78567, wal_write_time=294.08500000000004, wal_sync_time=38674.011000000006}

PostgreSQL per second: commits 4116, WAL flushes 1916, WAL flush time 943 ms (max 976 ms), WAL write time 7 ms
