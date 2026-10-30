# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/2-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.7 ms | 3.4 ms | 18.4 ms | 29.3 ms | 49.8 ms | 24.7 ms |

Load generator: 0.40 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.23 | 1457 MB | 797 MB | 22 | 0.523 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.4% | 119.3% | 498.7MiB / 31.36GiB |
| wow-e2e-bench-postgres | 88.7% | 101.2% | 230.8MiB / 31.36GiB |

Store operations, postgres (seconds -0.058 to 40.944): {epoch=41.00173091888428, xact_commit=195779, xact_rollback=0, tup_inserted=101778, tup_updated=881, tup_deleted=6, tup_fetched=659634, tup_returned=941768, blks_read=0, blks_hit=4155745, wal_records=521760, wal_fpi=2, wal_bytes=66261306, wal_buffers_full=0, wal_write=72741, wal_sync=72741, wal_write_time=367.39000000000004, wal_sync_time=37580.113999999994}

PostgreSQL per second: commits 4775, WAL flushes 1774, WAL flush time 917 ms (max 954 ms), WAL write time 9 ms
