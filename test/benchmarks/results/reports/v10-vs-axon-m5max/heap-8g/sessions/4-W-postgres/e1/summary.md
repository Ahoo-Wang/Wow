# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/4-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 8.1 ms | 123.4 ms | 177.2 ms | 200.9 ms | 181.2 ms |

Load generator: 0.40 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.10 | 6116 MB | 4937 MB | 2 | 0.027 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 26.3% | 107.3% | 361.7MiB / 31.36GiB |
| wow-e2e-bench-postgres | 53.6% | 78.4% | 634MiB / 31.36GiB |

Store operations, postgres (seconds -0.064 to 40.936): {epoch=41.000741958618164, xact_commit=151264, xact_rollback=0, tup_inserted=101523, tup_updated=222365, tup_deleted=7, tup_fetched=335397, tup_returned=393533, blks_read=0, blks_hit=2635173, wal_records=1030204, wal_fpi=2, wal_bytes=124296404, wal_buffers_full=0, wal_write=69326, wal_sync=69326, wal_write_time=267.622, wal_sync_time=38976.836}

PostgreSQL per second: commits 3689, WAL flushes 1691, WAL flush time 951 ms (max 1263 ms), WAL write time 7 ms
