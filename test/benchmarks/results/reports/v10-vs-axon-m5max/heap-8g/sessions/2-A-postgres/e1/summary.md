# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/2-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.6 ms | 2.7 ms | 5.6 ms | 17.2 ms | 34.1 ms | 19.8 ms |

Load generator: 0.40 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.03 | 5684 MB | 4851 MB | 2 | 0.020 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.3% | 102.9% | 362.3MiB / 31.36GiB |
| wow-e2e-bench-postgres | 85.5% | 94.0% | 233.3MiB / 31.36GiB |

Store operations, postgres (seconds -0.054 to 40.945): {epoch=40.99925494194031, xact_commit=197960, xact_rollback=0, tup_inserted=100120, tup_updated=848, tup_deleted=6, tup_fetched=603795, tup_returned=898710, blks_read=0, blks_hit=4029938, wal_records=513769, wal_fpi=2, wal_bytes=65248121, wal_buffers_full=0, wal_write=71287, wal_sync=71287, wal_write_time=351.49699999999996, wal_sync_time=36791.378000000004}

PostgreSQL per second: commits 4829, WAL flushes 1739, WAL flush time 897 ms (max 945 ms), WAL write time 9 ms
