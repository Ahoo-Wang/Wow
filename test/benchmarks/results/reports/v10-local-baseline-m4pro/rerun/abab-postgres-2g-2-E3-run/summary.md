# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-postgres-2g-2-up.

Open loop at 1428.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1394.5/s | 83672 | 2008 | 16.0 ms | 618.5 ms | 3915.8 ms | 4325.4 ms | 4726.1 ms | 4522.0 ms |

Errors: {http-429-wow.command.busy=2008}

Load generator: 0.34 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.69 | 1598 MB | 1646 MB | 15 | 0.273 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.8% | 153.4% | 2.177GiB / 15.6GiB |
| wow-e2e-bench-postgres | 138.6% | 374.3% | 1.14GiB / 15.6GiB |

Store operations, postgres (seconds -0.058 to 60.941): {epoch=60.99906802177429, xact_commit=284566, xact_rollback=0, tup_inserted=249788, tup_updated=468755, tup_deleted=0, tup_fetched=1465336, tup_returned=1472356, blks_read=969123, blks_hit=6198502, wal_records=2111529, wal_fpi=117940, wal_bytes=1027090318, wal_buffers_full=0, wal_write=119180, wal_sync=119180, wal_write_time=1496.4439999999995, wal_sync_time=50444.97200000007}
Store operations, kafkaEndOffsets (seconds -30.011 to 62.151): {wow.transfer.account.state=376710}

PostgreSQL per second: commits 4665, WAL flushes 1954, WAL flush time 827 ms (max 4151 ms), WAL write time 25 ms
