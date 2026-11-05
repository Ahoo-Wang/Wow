# m5max-axon-peak-2-axon-e3-own: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-peak-e3/2-axon-up.

Open loop at 1159.8/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1159.8/s | 46392 | 0 | 3.0 ms | 5.3 ms | 38.7 ms | 215.0 ms | 253.6 ms | 233.5 ms |

Load generator: 0.18 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.08 | 1610 MB | 792 MB | 25 | 0.570 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 79.0% | 87.0% | 584.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.018 to 40.982): {epoch=40.999873876571655, xact_commit=252572, xact_rollback=0, tup_inserted=185196, tup_updated=105243, tup_deleted=0, tup_fetched=562496, tup_returned=1076655, blks_read=798, blks_hit=4664797, wal_records=1009206, wal_fpi=0, wal_bytes=146463741, wal_buffers_full=0, wal_write=74054, wal_sync=74054, wal_write_time=290.0920000000001, wal_sync_time=35976.842000000004, checkpoint_buffers=0, bgwriter_buffers=16764}

PostgreSQL per second: commits 6160, WAL flushes 1806, WAL flush time 877 ms (max 955 ms), WAL write time 7 ms
