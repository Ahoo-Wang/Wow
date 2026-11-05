# m5max-axon-same-rate-2-axon-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 14, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-same-rate/2-axon-up.

Open loop at 1150.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1150.0/s | 46000 | 0 | 2.8 ms | 4.7 ms | 37.1 ms | 180.2 ms | 251.9 ms | 220.2 ms |

Load generator: 0.19 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.98 | 1614 MB | 937 MB | 23 | 0.482 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 76.6% | 94.6% | 395.2MiB / 31.36GiB |

Store operations, postgres (seconds -0.063 to 40.945): {epoch=41.007657051086426, xact_commit=256390, xact_rollback=0, tup_inserted=186054, tup_updated=100254, tup_deleted=6, tup_fetched=518892, tup_returned=2066712, blks_read=0, blks_hit=4720824, wal_records=1013117, wal_fpi=0, wal_bytes=144358970, wal_buffers_full=0, wal_write=76474, wal_sync=76474, wal_write_time=303.0880000000001, wal_sync_time=36538.19399999999, checkpoint_buffers=0, bgwriter_buffers=0}

PostgreSQL per second: commits 6253, WAL flushes 1865, WAL flush time 891 ms (max 967 ms), WAL write time 7 ms
