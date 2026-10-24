# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-postgres-2g-1-up.

Open loop at 1428.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1423.5/s | 85408 | 272 | 16.8 ms | 1245.2 ms | 2523.1 ms | 2965.5 ms | 3176.3 ms | 3096.6 ms |

Errors: {http-429-wow.command.busy=272}

Load generator: 0.45 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.77 | 1664 MB | 1667 MB | 15 | 0.304 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.3% | 161.7% | 2.197GiB / 15.6GiB |
| wow-e2e-bench-postgres | 159.7% | 356.6% | 1.001GiB / 15.6GiB |

Store operations, postgres (seconds -0.053 to 60.957): {epoch=61.00942301750183, xact_commit=250887, xact_rollback=0, tup_inserted=258124, tup_updated=462760, tup_deleted=0, tup_fetched=1649613, tup_returned=1678143, blks_read=1249768, blks_hit=6487457, wal_records=2212398, wal_fpi=115790, wal_bytes=1028201409, wal_buffers_full=0, wal_write=90306, wal_sync=90306, wal_write_time=1411.6490000000013, wal_sync_time=49229.14599999995}
Store operations, kafkaEndOffsets (seconds -29.954 to 62.214): {wow.transfer.account.state=381180}

PostgreSQL per second: commits 4113, WAL flushes 1480, WAL flush time 807 ms (max 2131 ms), WAL write time 23 ms
