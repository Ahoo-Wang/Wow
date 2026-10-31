# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/1-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 6.2 ms | 38.7 ms | 103.4 ms | 117.5 ms | 105.0 ms |

Load generator: 0.41 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.12 | 6111 MB | 5024 MB | 2 | 0.026 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 28.5% | 105.7% | 363.1MiB / 31.36GiB |
| wow-e2e-bench-postgres | 54.3% | 93.3% | 634.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.06 to 40.941): {epoch=41.00089693069458, xact_commit=155100, xact_rollback=0, tup_inserted=101220, tup_updated=222602, tup_deleted=7, tup_fetched=333860, tup_returned=391954, blks_read=0, blks_hit=2627801, wal_records=1031113, wal_fpi=2, wal_bytes=123894161, wal_buffers_full=0, wal_write=71255, wal_sync=71255, wal_write_time=275.62300000000005, wal_sync_time=38882.952000000005}

PostgreSQL per second: commits 3783, WAL flushes 1738, WAL flush time 948 ms (max 1090 ms), WAL write time 7 ms
