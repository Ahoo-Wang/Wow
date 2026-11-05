# m5max-axon-same-rate-2-axon-e1: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 14, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-same-rate/2-axon-up.

Open loop at 3850.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3657.1/s | 146282 | 7718 | 1.3 ms | 741.4 ms | 909.3 ms | 1097.7 ms | 1453.2 ms | 1179.6 ms |

Errors: {generator-overload=7718}

Load generator: 0.62 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.12 | 1584 MB | 969 MB | 20 | 0.470 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 62.3% | 84.6% | 266.3MiB / 31.36GiB |

Store operations, postgres (seconds -0.059 to 40.939): {epoch=40.99780797958374, xact_commit=234592, xact_rollback=0, tup_inserted=147047, tup_updated=1738, tup_deleted=6, tup_fetched=377606, tup_returned=771587, blks_read=0, blks_hit=3424386, wal_records=753502, wal_fpi=1, wal_bytes=97900428, wal_buffers_full=0, wal_write=66080, wal_sync=66080, wal_write_time=278.637, wal_sync_time=37617.713, checkpoint_buffers=0, bgwriter_buffers=0}

PostgreSQL per second: commits 5723, WAL flushes 1612, WAL flush time 918 ms (max 1246 ms), WAL write time 7 ms
