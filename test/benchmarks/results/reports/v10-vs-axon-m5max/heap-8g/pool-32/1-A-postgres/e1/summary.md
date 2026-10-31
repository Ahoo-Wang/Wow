# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 13, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/pool-32/1-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 2.4 ms | 91.6 ms | 153.6 ms | 174.1 ms | 215.8 ms | 173.1 ms |

Load generator: 0.42 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.92 | 5759 MB | 4809 MB | 3 | 0.028 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 28.9% | 121.5% | 358.2MiB / 31.36GiB |
| wow-e2e-bench-postgres | 76.6% | 105.5% | 278.9MiB / 31.36GiB |

Store operations, postgres (seconds -0.058 to 40.942): {epoch=41.00019979476929, xact_commit=177820, xact_rollback=0, tup_inserted=100420, tup_updated=3040, tup_deleted=6, tup_fetched=515298, tup_returned=982898, blks_read=0, blks_hit=3674622, wal_records=517662, wal_fpi=1, wal_bytes=65865845, wal_buffers_full=0, wal_write=44651, wal_sync=44651, wal_write_time=240.06099999999998, wal_sync_time=38256.381}

PostgreSQL per second: commits 4337, WAL flushes 1089, WAL flush time 933 ms (max 1239 ms), WAL write time 6 ms
