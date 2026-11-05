# m5max-axon-same-rate-1-axon-e1: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 14, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-same-rate/1-axon-up.

Open loop at 3850.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3850.0/s | 154000 | 0 | 1.1 ms | 3.3 ms | 32.6 ms | 129.0 ms | 252.7 ms | 184.3 ms |

Load generator: 0.59 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.18 | 1580 MB | 946 MB | 21 | 0.554 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 70.2% | 82.4% | 267.1MiB / 31.36GiB |

Store operations, postgres (seconds -0.034 to 40.969): {epoch=41.0029571056366, xact_commit=267193, xact_rollback=0, tup_inserted=154533, tup_updated=1547, tup_deleted=6, tup_fetched=452597, tup_returned=942184, blks_read=1, blks_hit=4050004, wal_records=791111, wal_fpi=1, wal_bytes=102433186, wal_buffers_full=0, wal_write=78137, wal_sync=78137, wal_write_time=321.52900000000005, wal_sync_time=37249.501000000004, checkpoint_buffers=0, bgwriter_buffers=0}

PostgreSQL per second: commits 6517, WAL flushes 1906, WAL flush time 909 ms (max 981 ms), WAL write time 8 ms
