# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-postgres-2g-2-up.

Open loop at 6092.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5657.6/s | 339453 | 26066 | 14.1 ms | 175.1 ms | 3964.9 ms | 4456.4 ms | 4617.3 ms | 4489.2 ms |

Errors: {http-429-wow.command.busy=26066}

Load generator: 1.52 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.19 | 1667 MB | 1589 MB | 24 | 0.278 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 44.4% | 157.1% | 2.165GiB / 15.6GiB |
| wow-e2e-bench-postgres | 168.9% | 277.8% | 1.152GiB / 15.6GiB |

Store operations, postgres (seconds -0.984 to 60.023): {epoch=61.006383180618286, xact_commit=269755, xact_rollback=0, tup_inserted=345194, tup_updated=605971, tup_deleted=12, tup_fetched=1522211, tup_returned=1554647, blks_read=852092, blks_hit=8643042, wal_records=2864510, wal_fpi=47398, wal_bytes=689682759, wal_buffers_full=0, wal_write=92307, wal_sync=92307, wal_write_time=1325.3889999999992, wal_sync_time=51772.63399999996}
Store operations, kafkaEndOffsets (seconds -29.781 to 62.212): {wow.transfer.account.state=498583}

PostgreSQL per second: commits 4422, WAL flushes 1513, WAL flush time 849 ms (max 4909 ms), WAL write time 22 ms
