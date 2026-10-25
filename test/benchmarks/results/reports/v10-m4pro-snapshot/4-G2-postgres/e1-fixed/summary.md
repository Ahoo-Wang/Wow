# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 5, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261025-000029-run-postgres-n1-saturation.

Open loop at 8064.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7189.2/s | 431350 | 52490 | 20.4 ms | 232.4 ms | 1925.1 ms | 3375.1 ms | 5084.0 ms | 4718.6 ms |

Errors: {http-429-wow.command.busy=52490}

Load generator: 1.88 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.51 | 1745 MB | 1647 MB | 32 | 0.398 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.0% | 178.3% | 2.045GiB / 15.6GiB |
| wow-e2e-bench-postgres | 179.1% | 357.0% | 943.6MiB / 15.6GiB |

Store operations, postgres (seconds -0.98 to 60.019): {epoch=60.999228954315186, xact_commit=255598, xact_rollback=0, tup_inserted=440724, tup_updated=702643, tup_deleted=7, tup_fetched=1097833, tup_returned=1681484, blks_read=22311, blks_hit=10418776, wal_records=3354634, wal_fpi=0, wal_bytes=485212752, wal_buffers_full=0, wal_write=99367, wal_sync=99367, wal_write_time=930.4300000000003, wal_sync_time=45330.80500000002}
Store operations, kafkaEndOffsets (seconds -29.744 to 62.314): {wow.transfer.account.state=663698}

PostgreSQL per second: commits 4190, WAL flushes 1629, WAL flush time 743 ms (max 2877 ms), WAL write time 15 ms
