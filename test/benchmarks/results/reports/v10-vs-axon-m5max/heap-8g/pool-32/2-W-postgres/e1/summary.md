# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/pool-32/2-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 4.4 ms | 9.5 ms | 145.4 ms | 184.0 ms | 167.9 ms |

Load generator: 0.41 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.17 | 6083 MB | 4950 MB | 2 | 0.042 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 26.8% | 106.8% | 364.8MiB / 31.36GiB |
| wow-e2e-bench-postgres | 57.9% | 88.6% | 668.1MiB / 31.36GiB |

Store operations, postgres (seconds -0.056 to 40.946): {epoch=41.001842975616455, xact_commit=158006, xact_rollback=0, tup_inserted=100972, tup_updated=222555, tup_deleted=6, tup_fetched=328432, tup_returned=854926, blks_read=0, blks_hit=3135859, wal_records=1055213, wal_fpi=1, wal_bytes=124458067, wal_buffers_full=0, wal_write=72326, wal_sync=72326, wal_write_time=289.127, wal_sync_time=38463.221000000005}

PostgreSQL per second: commits 3854, WAL flushes 1764, WAL flush time 938 ms (max 1054 ms), WAL write time 7 ms
