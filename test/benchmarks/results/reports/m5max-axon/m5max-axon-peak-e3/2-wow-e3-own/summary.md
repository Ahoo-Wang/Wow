# m5max-axon-peak-2-wow-e3-own: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores postgres, storeWriteIops , primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/m5max-refresh-0bada9/test/benchmarks/results/e2e/m5max-axon/m5max-axon-peak-e3/2-wow-up.

Open loop at 5834.1/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5743.3/s | 229733 | 3631 | 68.6 ms | 252.9 ms | 434.2 ms | 514.0 ms | 615.7 ms | 522.2 ms |

Errors: {generator-overload=3631}

Load generator: 1.02 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.22 | 1495 MB | 833 MB | 90 | 0.281 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-postgres | 143.9% | 192.5% | 663.1MiB / 31.36GiB |

Store operations, postgres (seconds -0.065 to 40.933): {epoch=40.99848008155823, xact_commit=63637, xact_rollback=0, tup_inserted=695552, tup_updated=862857, tup_deleted=0, tup_fetched=1708080, tup_returned=1712430, blks_read=503141, blks_hit=15432548, wal_records=4655774, wal_fpi=0, wal_bytes=905038677, wal_buffers_full=0, wal_write=16110, wal_sync=16110, wal_write_time=156.52599999999984, wal_sync_time=17173.18100000001, checkpoint_buffers=162, bgwriter_buffers=20000}

PostgreSQL per second: commits 1552, WAL flushes 393, WAL flush time 419 ms (max 694 ms), WAL write time 4 ms
