# m5max-axon-peak-2-axon-e1-own: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 14, settleSeconds 7, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-peak-e1/2-axon-up.

Open loop at 3858.1/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3858.1/s | 154324 | 0 | 1.2 ms | 1.8 ms | 9.3 ms | 57.6 ms | 114.8 ms | 77.8 ms |

Load generator: 0.67 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.16 | 1576 MB | 714 MB | 32 | 0.182 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 72.6% | 97.7% | 582.9MiB / 31.36GiB |

Store operations, postgres (seconds -0.042 to 40.958): {epoch=41.00009298324585, xact_commit=268362, xact_rollback=0, tup_inserted=158070, tup_updated=2041, tup_deleted=6, tup_fetched=450556, tup_returned=946222, blks_read=15613, blks_hit=3936521, wal_records=802819, wal_fpi=0, wal_bytes=106946814, wal_buffers_full=0, wal_write=78956, wal_sync=78956, wal_write_time=345.578, wal_sync_time=38286.58500000002, checkpoint_buffers=0, bgwriter_buffers=4027}

PostgreSQL per second: commits 6546, WAL flushes 1926, WAL flush time 934 ms (max 963 ms), WAL write time 8 ms
