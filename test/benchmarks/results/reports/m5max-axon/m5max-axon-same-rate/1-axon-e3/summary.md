# m5max-axon-same-rate-1-axon-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 14, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-same-rate/1-axon-up.

Open loop at 1150.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1150.0/s | 46000 | 0 | 2.8 ms | 4.6 ms | 29.7 ms | 106.5 ms | 166.8 ms | 132.1 ms |

Load generator: 0.18 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.98 | 1622 MB | 961 MB | 23 | 0.493 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 76.6% | 93.9% | 397.9MiB / 31.36GiB |

Store operations, postgres (seconds -0.03 to 40.965): {epoch=40.995595932006836, xact_commit=254903, xact_rollback=0, tup_inserted=184547, tup_updated=98920, tup_deleted=6, tup_fetched=519737, tup_returned=2023334, blks_read=0, blks_hit=4704319, wal_records=1003774, wal_fpi=1, wal_bytes=142878650, wal_buffers_full=0, wal_write=76386, wal_sync=76386, wal_write_time=297.9530000000001, wal_sync_time=36269.10800000001, checkpoint_buffers=0, bgwriter_buffers=0}

PostgreSQL per second: commits 6218, WAL flushes 1863, WAL flush time 885 ms (max 1026 ms), WAL write time 7 ms
