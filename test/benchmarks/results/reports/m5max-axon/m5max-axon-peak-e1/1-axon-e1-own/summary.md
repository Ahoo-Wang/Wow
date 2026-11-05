# m5max-axon-peak-1-axon-e1-own: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 14, settleSeconds 7, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-peak-e1/1-axon-up.

Open loop at 3862.5/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3862.5/s | 154500 | 0 | 1.2 ms | 2.1 ms | 64.3 ms | 244.7 ms | 368.5 ms | 297.0 ms |

Load generator: 0.61 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.11 | 1586 MB | 711 MB | 24 | 0.173 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 72.5% | 94.7% | 583.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.045 to 40.958): {epoch=41.00295686721802, xact_commit=271072, xact_rollback=0, tup_inserted=157065, tup_updated=4380, tup_deleted=6, tup_fetched=451514, tup_returned=990201, blks_read=15502, blks_hit=3966470, wal_records=802452, wal_fpi=0, wal_bytes=106703590, wal_buffers_full=0, wal_write=78625, wal_sync=78625, wal_write_time=327.75700000000006, wal_sync_time=38063.21799999999, checkpoint_buffers=0, bgwriter_buffers=3925}

PostgreSQL per second: commits 6612, WAL flushes 1918, WAL flush time 928 ms (max 958 ms), WAL write time 8 ms
