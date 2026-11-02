# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/pool-32/1-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.8 ms | 3.3 ms | 17.3 ms | 30.3 ms | 227.6 ms | 42.8 ms |

Load generator: 0.46 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.29 | 1447 MB | 803 MB | 22 | 0.527 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.8% | 146.4% | 368MiB / 31.36GiB |
| wow-e2e-bench-postgres | 81.7% | 100.6% | 277.7MiB / 31.36GiB |

Store operations, postgres (seconds -0.038 to 40.964): {epoch=41.00133299827576, xact_commit=187632, xact_rollback=0, tup_inserted=101305, tup_updated=929, tup_deleted=6, tup_fetched=607243, tup_returned=857113, blks_read=0, blks_hit=3829898, wal_records=518185, wal_fpi=1, wal_bytes=65804161, wal_buffers_full=0, wal_write=73431, wal_sync=73431, wal_write_time=345.7830000000001, wal_sync_time=37371.984}

PostgreSQL per second: commits 4576, WAL flushes 1791, WAL flush time 911 ms (max 1039 ms), WAL write time 8 ms
