# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/1-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.0 ms | 3.8 ms | 6.4 ms | 16.3 ms | 24.9 ms | 19.3 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.96 | 6275 MB | 5193 MB | 1 | 0.012 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 24.8% | 99.3% | 366.2MiB / 31.36GiB |
| wow-e2e-bench-postgres | 49.0% | 74.8% | 731.7MiB / 31.36GiB |

Store operations, postgres (seconds -0.059 to 40.944): {epoch=41.00276207923889, xact_commit=162730, xact_rollback=0, tup_inserted=84671, tup_updated=191462, tup_deleted=7, tup_fetched=243557, tup_returned=287828, blks_read=0, blks_hit=2271216, wal_records=857439, wal_fpi=0, wal_bytes=123008101, wal_buffers_full=0, wal_write=85047, wal_sync=85047, wal_write_time=297.23800000000006, wal_sync_time=37455.87400000001}

PostgreSQL per second: commits 3969, WAL flushes 2074, WAL flush time 914 ms (max 942 ms), WAL write time 7 ms
