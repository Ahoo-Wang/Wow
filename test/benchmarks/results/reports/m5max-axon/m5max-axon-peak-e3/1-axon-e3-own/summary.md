# m5max-axon-peak-1-axon-e3-own: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 14, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-peak-e3/1-axon-up.

Open loop at 1157.2/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1083.7/s | 43346 | 2942 | 4.0 ms | 5341.2 ms | 7340.0 ms | 8093.7 ms | 8235.1 ms | 8224.8 ms |

Errors: {generator-overload=2942}

Load generator: 0.21 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.93 | 1605 MB | 700 MB | 26 | 0.272 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 67.5% | 118.2% | 584.4MiB / 31.36GiB |

Store operations, postgres (seconds -0.041 to 39.959): {epoch=40.000216007232666, xact_commit=207214, xact_rollback=0, tup_inserted=181281, tup_updated=101915, tup_deleted=0, tup_fetched=844069, tup_returned=1158178, blks_read=807, blks_hit=4031939, wal_records=984022, wal_fpi=0, wal_bytes=142426488, wal_buffers_full=0, wal_write=52814, wal_sync=52814, wal_write_time=206.1500000000001, wal_sync_time=38144.813999999955, checkpoint_buffers=0, bgwriter_buffers=18806}

PostgreSQL per second: commits 5180, WAL flushes 1320, WAL flush time 954 ms (max 1189 ms), WAL write time 5 ms
