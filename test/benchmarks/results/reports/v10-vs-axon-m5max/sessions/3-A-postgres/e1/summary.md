# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/3-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.7 ms | 3.8 ms | 23.7 ms | 39.9 ms | 67.9 ms | 45.8 ms |

Load generator: 0.50 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.26 | 1469 MB | 847 MB | 24 | 0.545 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 29.0% | 90.9% | 416.5MiB / 31.36GiB |
| wow-e2e-bench-postgres | 87.0% | 100.2% | 230MiB / 31.36GiB |

Store operations, postgres (seconds -0.066 to 40.932): {epoch=40.99787783622742, xact_commit=194728, xact_rollback=0, tup_inserted=101833, tup_updated=850, tup_deleted=6, tup_fetched=657709, tup_returned=934324, blks_read=2, blks_hit=4133342, wal_records=522658, wal_fpi=4, wal_bytes=66097443, wal_buffers_full=0, wal_write=72870, wal_sync=72870, wal_write_time=360.997, wal_sync_time=37477.244999999995}

PostgreSQL per second: commits 4750, WAL flushes 1777, WAL flush time 914 ms (max 969 ms), WAL write time 9 ms
