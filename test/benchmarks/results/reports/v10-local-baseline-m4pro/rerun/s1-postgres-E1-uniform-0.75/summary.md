# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-postgres-up.

Open loop at 8424.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8383.1/s | 502983 | 2457 | 111.1 ms | 378.9 ms | 626.7 ms | 843.8 ms | 1522.5 ms | 946.2 ms |

Errors: {http-429-wow.command.busy=2457}

Load generator: 1.87 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.62 | 1108 MB | 876 MB | 132 | 0.652 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.1% | 156.8% | 2.099GiB / 15.6GiB |
| wow-e2e-bench-postgres | 306.3% | 334.8% | 767.4MiB / 15.6GiB |

Store operations, postgres (seconds -0.058 to 60.943): {epoch=61.00028991699219, xact_commit=301244, xact_rollback=0, tup_inserted=504748, tup_updated=605161, tup_deleted=0, tup_fetched=10331879, tup_returned=10341963, blks_read=7192880, blks_hit=15258006, wal_records=3476452, wal_fpi=62408, wal_bytes=897571206, wal_buffers_full=0, wal_write=51482, wal_sync=51482, wal_write_time=963.0079999999998, wal_sync_time=29516.66599999997}
Store operations, kafkaEndOffsets (seconds -30.023 to 62.193): {wow.transfer.account.state=751251}

PostgreSQL per second: commits 4938, WAL flushes 844, WAL flush time 484 ms (max 744 ms), WAL write time 16 ms
