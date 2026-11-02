# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 15, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/heap-8g/pool-32/1-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.6 ms | 5.7 ms | 8.4 ms | 17.4 ms | 30.4 ms | 26.1 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.71 | 5728 MB | 4838 MB | 2 | 0.022 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.4% | 121.7% | 367.4MiB / 31.36GiB |
| wow-e2e-bench-postgres | 71.3% | 88.2% | 366.2MiB / 31.36GiB |

Store operations, postgres (seconds -0.064 to 40.94): {epoch=41.00358295440674, xact_commit=191445, xact_rollback=0, tup_inserted=112507, tup_updated=57460, tup_deleted=6, tup_fetched=672088, tup_returned=1523068, blks_read=0, blks_hit=4261843, wal_records=606760, wal_fpi=0, wal_bytes=89947509, wal_buffers_full=0, wal_write=69636, wal_sync=69636, wal_write_time=307.20399999999995, wal_sync_time=32087.481}

PostgreSQL per second: commits 4670, WAL flushes 1699, WAL flush time 783 ms (max 864 ms), WAL write time 7 ms
