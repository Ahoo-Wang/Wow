# m5max-axon-same-rate-1-wow-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-same-rate/1-wow-up.

Open loop at 1150.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1150.0/s | 46000 | 0 | 3.3 ms | 4.7 ms | 7.6 ms | 78.8 ms | 113.4 ms | 102.4 ms |

Load generator: 0.19 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.32 | 1340 MB | 812 MB | 17 | 0.060 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 66.3% | 92.8% | 833MiB / 31.36GiB |

Store operations, postgres (seconds -0.061 to 40.946): {epoch=41.00706911087036, xact_commit=205583, xact_rollback=0, tup_inserted=139103, tup_updated=299166, tup_deleted=7, tup_fetched=499569, tup_returned=560542, blks_read=0, blks_hit=3924707, wal_records=1417366, wal_fpi=1, wal_bytes=203562984, wal_buffers_full=0, wal_write=71803, wal_sync=71803, wal_write_time=225.51599999999996, wal_sync_time=38837.543999999994, checkpoint_buffers=0, bgwriter_buffers=0}

PostgreSQL per second: commits 5014, WAL flushes 1751, WAL flush time 947 ms (max 995 ms), WAL write time 5 ms
