# m5max-axon-same-rate-2-wow-e1: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-same-rate/2-wow-up.

Open loop at 3850.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3850.0/s | 154000 | 0 | 1.8 ms | 2.9 ms | 5.0 ms | 13.4 ms | 27.5 ms | 17.4 ms |

Load generator: 0.58 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.48 | 1299 MB | 764 MB | 23 | 0.079 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 71.5% | 92.2% | 681.5MiB / 31.36GiB |

Store operations, postgres (seconds -0.073 to 40.937): {epoch=41.00922918319702, xact_commit=210533, xact_rollback=0, tup_inserted=154831, tup_updated=330276, tup_deleted=7, tup_fetched=582250, tup_returned=655788, blks_read=0, blks_hit=4132636, wal_records=1596459, wal_fpi=3, wal_bytes=197275210, wal_buffers_full=0, wal_write=64556, wal_sync=64556, wal_write_time=240.75100000000003, wal_sync_time=38589.624, checkpoint_buffers=0, bgwriter_buffers=0}

PostgreSQL per second: commits 5135, WAL flushes 1574, WAL flush time 941 ms (max 972 ms), WAL write time 6 ms
