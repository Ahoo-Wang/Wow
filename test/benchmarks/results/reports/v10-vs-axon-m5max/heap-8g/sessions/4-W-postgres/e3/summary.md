# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/4-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.1 ms | 4.1 ms | 7.4 ms | 19.5 ms | 30.1 ms | 24.6 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.95 | 6266 MB | 5077 MB | 1 | 0.012 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 25.6% | 106.8% | 363.1MiB / 31.36GiB |
| wow-e2e-bench-postgres | 49.5% | 88.3% | 729.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.059 to 40.94): {epoch=40.99829411506653, xact_commit=161038, xact_rollback=0, tup_inserted=84585, tup_updated=190633, tup_deleted=7, tup_fetched=243425, tup_returned=288094, blks_read=0, blks_hit=2267269, wal_records=854260, wal_fpi=0, wal_bytes=122909317, wal_buffers_full=0, wal_write=83781, wal_sync=83781, wal_write_time=299.3660000000001, wal_sync_time=37248.350999999995}

PostgreSQL per second: commits 3928, WAL flushes 2044, WAL flush time 909 ms (max 975 ms), WAL write time 7 ms
