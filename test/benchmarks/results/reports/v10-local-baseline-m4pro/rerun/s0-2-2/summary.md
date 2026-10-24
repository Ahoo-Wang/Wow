# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s0-up-2.

Open loop at 6346.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6346.0/s | 380760 | 0 | 7.6 ms | 123.9 ms | 319.5 ms | 561.2 ms | 743.4 ms | 618.5 ms |

Load generator: 1.77 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.92 | 1181 MB | 840 MB | 58 | 0.268 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.4% | 127.3% | 2.009GiB / 15.6GiB |
| wow-e2e-bench-postgres | 135.1% | 192.1% | 989.3MiB / 15.6GiB |

Store operations, postgres (seconds -0.055 to 60.946): {epoch=61.0007700920105, xact_commit=396794, xact_rollback=0, tup_inserted=381983, tup_updated=718812, tup_deleted=7, tup_fetched=1444025, tup_returned=1550832, blks_read=67170, blks_hit=9650589, wal_records=3218519, wal_fpi=12459, wal_bytes=511561243, wal_buffers_full=0, wal_write=129653, wal_sync=129653, wal_write_time=1135.924, wal_sync_time=44530.236000000004}
Store operations, kafkaEndOffsets (seconds -30.024 to 62.06): {wow.transfer.account.state=556557}

PostgreSQL per second: commits 6505, WAL flushes 2125, WAL flush time 730 ms (max 898 ms), WAL write time 19 ms
