# m5max-axon-peak-1-wow-e3-own: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-peak-e3/1-wow-up.

Open loop at 5827.3/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5752.7/s | 230107 | 2985 | 69.1 ms | 155.6 ms | 454.7 ms | 544.8 ms | 636.8 ms | 569.3 ms |

Errors: {generator-overload=2985}

Load generator: 1.03 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.26 | 1498 MB | 855 MB | 87 | 0.283 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 145.8% | 202.3% | 677.2MiB / 31.36GiB |

Store operations, postgres (seconds -0.046 to 40.963): {epoch=41.009145975112915, xact_commit=65897, xact_rollback=0, tup_inserted=696153, tup_updated=872188, tup_deleted=0, tup_fetched=1723398, tup_returned=1727127, blks_read=508808, blks_hit=15495498, wal_records=4674867, wal_fpi=1, wal_bytes=906754339, wal_buffers_full=0, wal_write=16322, wal_sync=16322, wal_write_time=150.7600000000001, wal_sync_time=16907.652000000002, checkpoint_buffers=134, bgwriter_buffers=20100}

PostgreSQL per second: commits 1607, WAL flushes 398, WAL flush time 412 ms (max 731 ms), WAL write time 4 ms
