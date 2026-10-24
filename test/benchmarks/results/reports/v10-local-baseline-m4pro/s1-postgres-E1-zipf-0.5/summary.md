# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-postgres-up.

Open loop at 2256.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2174.6/s | 130475 | 4885 | 1.3 ms | 1253.4 ms | 3686.4 ms | 4390.9 ms | 4505.5 ms | 4489.2 ms |

Errors: {http-429-wow.command.busy=4885}

Load generator: 0.41 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.15 | 1041 MB | 801 MB | 20 | 0.084 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.1% | 112.2% | 2.167GiB / 15.6GiB |
| wow-e2e-bench-postgres | 56.1% | 108.0% | 866.9MiB / 15.6GiB |

Store operations, postgres (seconds -0.056 to 60.943): {epoch=60.998465061187744, xact_commit=226065, xact_rollback=0, tup_inserted=131492, tup_updated=283288, tup_deleted=0, tup_fetched=673083, tup_returned=685589, blks_read=198164, blks_hit=3241518, wal_records=1223347, wal_fpi=18736, wal_bytes=266461552, wal_buffers_full=0, wal_write=151027, wal_sync=151027, wal_write_time=784.7489999999998, wal_sync_time=45063.826}
Store operations, kafkaEndOffsets (seconds -30.031 to 62.051): {wow.transfer.account.state=191049}

PostgreSQL per second: commits 3706, WAL flushes 2476, WAL flush time 739 ms (max 4146 ms), WAL write time 13 ms
