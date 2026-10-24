# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s0-up-1.

Open loop at 6346.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6346.0/s | 380760 | 0 | 22.3 ms | 75.3 ms | 284.7 ms | 460.8 ms | 798.2 ms | 618.5 ms |

Load generator: 1.62 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.85 | 1135 MB | 848 MB | 73 | 0.259 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.2% | 135.1% | 2.201GiB / 15.6GiB |
| wow-e2e-bench-postgres | 192.3% | 236.6% | 1.215GiB / 15.6GiB |

Store operations, postgres (seconds -0.008 to 59.992): {epoch=60.00029802322388, xact_commit=427307, xact_rollback=0, tup_inserted=381975, tup_updated=654357, tup_deleted=0, tup_fetched=7340625, tup_returned=7362154, blks_read=4901064, blks_hit=11920445, wal_records=3045777, wal_fpi=32365, wal_bytes=616996744, wal_buffers_full=0, wal_write=114616, wal_sync=114616, wal_write_time=1067.2559999999994, wal_sync_time=48428.962}
Store operations, kafkaEndOffsets (seconds -29.743 to 62.078): {wow.transfer.account.state=564725}

PostgreSQL per second: commits 7122, WAL flushes 1910, WAL flush time 807 ms (max 898 ms), WAL write time 18 ms
