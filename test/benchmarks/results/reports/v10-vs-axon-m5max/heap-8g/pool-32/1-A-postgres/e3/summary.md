# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 13, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/pool-32/1-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.4 ms | 5.2 ms | 7.5 ms | 17.7 ms | 31.4 ms | 27.6 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.73 | 5761 MB | 4918 MB | 1 | 0.010 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 27.9% | 115.3% | 361.1MiB / 31.36GiB |
| wow-e2e-bench-postgres | 78.2% | 102.7% | 364.5MiB / 31.36GiB |

Store operations, postgres (seconds -0.065 to 40.941): {epoch=41.005313873291016, xact_commit=200720, xact_rollback=0, tup_inserted=112221, tup_updated=58766, tup_deleted=6, tup_fetched=689916, tup_returned=1676420, blks_read=0, blks_hit=4434046, wal_records=608194, wal_fpi=0, wal_bytes=89473697, wal_buffers_full=0, wal_write=67236, wal_sync=67236, wal_write_time=351.68000000000006, wal_sync_time=32335.451}

PostgreSQL per second: commits 4896, WAL flushes 1640, WAL flush time 789 ms (max 879 ms), WAL write time 9 ms
