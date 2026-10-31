# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 23, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/3-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.6 ms | 2.9 ms | 9.7 ms | 42.0 ms | 70.1 ms | 46.8 ms |

Load generator: 0.40 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.02 | 5727 MB | 4921 MB | 2 | 0.021 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.7% | 133.5% | 362MiB / 31.36GiB |
| wow-e2e-bench-postgres | 87.1% | 106.2% | 230.5MiB / 31.36GiB |

Store operations, postgres (seconds -0.044 to 40.959): {epoch=41.00293493270874, xact_commit=194016, xact_rollback=0, tup_inserted=100127, tup_updated=878, tup_deleted=6, tup_fetched=589280, tup_returned=875387, blks_read=0, blks_hit=3937987, wal_records=513300, wal_fpi=3, wal_bytes=65118107, wal_buffers_full=0, wal_write=70324, wal_sync=70324, wal_write_time=335.0919999999999, wal_sync_time=36844.593}

PostgreSQL per second: commits 4732, WAL flushes 1715, WAL flush time 899 ms (max 953 ms), WAL write time 8 ms
