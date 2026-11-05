# m5max-axon-same-rate-1-wow-e1: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-same-rate/1-wow-up.

Open loop at 3850.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3850.0/s | 154000 | 0 | 1.7 ms | 2.9 ms | 4.9 ms | 20.0 ms | 42.7 ms | 35.6 ms |

Load generator: 0.57 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.48 | 1302 MB | 709 MB | 23 | 0.079 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 71.2% | 93.6% | 675.3MiB / 31.36GiB |

Store operations, postgres (seconds -0.053 to 40.954): {epoch=41.00676393508911, xact_commit=212411, xact_rollback=0, tup_inserted=155744, tup_updated=331555, tup_deleted=7, tup_fetched=588311, tup_returned=659749, blks_read=0, blks_hit=4158654, wal_records=1604739, wal_fpi=3, wal_bytes=198409525, wal_buffers_full=0, wal_write=65560, wal_sync=65560, wal_write_time=235.493, wal_sync_time=38852.128, checkpoint_buffers=0, bgwriter_buffers=0}

PostgreSQL per second: commits 5180, WAL flushes 1599, WAL flush time 948 ms (max 981 ms), WAL write time 6 ms
