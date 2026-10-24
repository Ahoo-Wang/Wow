# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-postgres-1g-1-up.

Open loop at 6092.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6034.9/s | 362096 | 3423 | 125.4 ms | 606.2 ms | 954.4 ms | 1327.1 ms | 1967.6 ms | 1556.5 ms |

Errors: {http-429-wow.command.busy=3423}

Load generator: 1.48 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.19 | 1150 MB | 838 MB | 109 | 0.724 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.8% | 164.6% | 2.157GiB / 15.6GiB |
| wow-e2e-bench-postgres | 342.7% | 429.4% | 864.6MiB / 15.6GiB |

Store operations, postgres (seconds -0.04 to 59.968): {epoch=60.00878381729126, xact_commit=263140, xact_rollback=0, tup_inserted=362123, tup_updated=459189, tup_deleted=12, tup_fetched=11365258, tup_returned=11403587, blks_read=9177412, blks_hit=11627787, wal_records=2598874, wal_fpi=38178, wal_bytes=632751662, wal_buffers_full=0, wal_write=45654, wal_sync=45654, wal_write_time=1483.4079999999994, wal_sync_time=34027.35099999991}
Store operations, kafkaEndOffsets (seconds -29.805 to 62.4): {wow.transfer.account.state=536599}

PostgreSQL per second: commits 4385, WAL flushes 761, WAL flush time 567 ms (max 866 ms), WAL write time 25 ms
