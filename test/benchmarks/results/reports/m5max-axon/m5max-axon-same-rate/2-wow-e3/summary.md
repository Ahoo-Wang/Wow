# m5max-axon-same-rate-2-wow-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-same-rate/2-wow-up.

Open loop at 1150.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1150.0/s | 46000 | 0 | 3.4 ms | 4.8 ms | 8.5 ms | 65.3 ms | 94.9 ms | 88.1 ms |

Load generator: 0.18 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.33 | 1338 MB | 818 MB | 18 | 0.064 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 66.8% | 95.4% | 842.8MiB / 31.36GiB |

Store operations, postgres (seconds -0.061 to 40.938): {epoch=40.99905180931091, xact_commit=203798, xact_rollback=0, tup_inserted=138136, tup_updated=296519, tup_deleted=7, tup_fetched=494461, tup_returned=556686, blks_read=0, blks_hit=3897229, wal_records=1406947, wal_fpi=1, wal_bytes=201933900, wal_buffers_full=0, wal_write=70846, wal_sync=70846, wal_write_time=227.86800000000005, wal_sync_time=38551.11200000001, checkpoint_buffers=0, bgwriter_buffers=0}

PostgreSQL per second: commits 4972, WAL flushes 1728, WAL flush time 940 ms (max 1072 ms), WAL write time 6 ms
