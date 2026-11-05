# m5max-axon-peak-2-wow-e1-own: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-peak-e1/2-wow-up.

Open loop at 18671.9/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 18599.9/s | 743994 | 2881 | 16.5 ms | 54.5 ms | 68.6 ms | 246.8 ms | 304.2 ms | 280.6 ms |

Errors: {generator-overload=2881}

Load generator: 2.74 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.84 | 1497 MB | 840 MB | 160 | 0.432 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 177.8% | 213.1% | 790.1MiB / 31.36GiB |

Store operations, postgres (seconds -0.052 to 40.946): {epoch=40.99828481674194, xact_commit=115933, xact_rollback=0, tup_inserted=748725, tup_updated=1038684, tup_deleted=8, tup_fetched=2078360, tup_returned=2438262, blks_read=194476, blks_hit=17760598, wal_records=5489374, wal_fpi=48, wal_bytes=864268030, wal_buffers_full=0, wal_write=20193, wal_sync=20193, wal_write_time=161.442, wal_sync_time=20317.603000000003, checkpoint_buffers=6957, bgwriter_buffers=20100}

PostgreSQL per second: commits 2828, WAL flushes 493, WAL flush time 496 ms (max 614 ms), WAL write time 4 ms
