# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-postgres-up.

Open loop at 854.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 854.0/s | 51240 | 0 | 5.5 ms | 565.2 ms | 1097.7 ms | 1613.8 ms | 1855.8 ms | 1687.6 ms |

Load generator: 0.23 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.40 | 973 MB | 838 MB | 29 | 0.244 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.0% | 175.6% | 2.217GiB / 15.6GiB |
| wow-e2e-bench-postgres | 169.8% | 399.1% | 924.3MiB / 15.6GiB |

Store operations, postgres (seconds -0.072 to 60.928): {epoch=60.99961709976196, xact_commit=283621, xact_rollback=0, tup_inserted=153915, tup_updated=318148, tup_deleted=0, tup_fetched=4778908, tup_returned=4786427, blks_read=3736720, blks_hit=5600036, wal_records=1411755, wal_fpi=51656, wal_bytes=525164302, wal_buffers_full=0, wal_write=135285, wal_sync=135284, wal_write_time=2832.872000000003, wal_sync_time=45222.716999999946}
Store operations, kafkaEndOffsets (seconds -30.038 to 62.136): {wow.transfer.account.state=242174}

PostgreSQL per second: commits 4649, WAL flushes 2218, WAL flush time 741 ms (max 1395 ms), WAL write time 46 ms
