# m5max-axon-peak-1-wow-e1-own: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-peak-e1/1-wow-up.

Open loop at 18655.7/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 18558.5/s | 742341 | 3887 | 16.1 ms | 54.5 ms | 77.3 ms | 209.9 ms | 311.8 ms | 234.5 ms |

Errors: {generator-overload=3887}

Load generator: 2.75 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.83 | 1508 MB | 847 MB | 155 | 0.445 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 178.7% | 203.3% | 787.9MiB / 31.36GiB |

Store operations, postgres (seconds -0.037 to 40.972): {epoch=41.00884699821472, xact_commit=115325, xact_rollback=0, tup_inserted=741248, tup_updated=1030568, tup_deleted=7, tup_fetched=2064336, tup_returned=3790735, blks_read=196497, blks_hit=18951038, wal_records=5442357, wal_fpi=131, wal_bytes=855016008, wal_buffers_full=0, wal_write=20153, wal_sync=20153, wal_write_time=155.06999999999994, wal_sync_time=19616.743000000002, checkpoint_buffers=6868, bgwriter_buffers=20100}

PostgreSQL per second: commits 2812, WAL flushes 491, WAL flush time 478 ms (max 640 ms), WAL write time 4 ms
