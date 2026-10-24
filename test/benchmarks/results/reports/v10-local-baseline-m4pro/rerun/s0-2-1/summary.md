# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s0-up-2.

Open loop at 6346.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6346.0/s | 380760 | 0 | 4.0 ms | 16.9 ms | 76.8 ms | 260.1 ms | 372.2 ms | 305.2 ms |

Load generator: 1.83 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.24 | 1300 MB | 825 MB | 54 | 0.224 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.9% | 144.1% | 1.986GiB / 15.6GiB |
| wow-e2e-bench-postgres | 128.3% | 177.0% | 1008MiB / 15.6GiB |

Store operations, postgres (seconds -0.058 to 60.943): {epoch=61.00091290473938, xact_commit=438043, xact_rollback=0, tup_inserted=376405, tup_updated=756628, tup_deleted=7, tup_fetched=1130000, tup_returned=1230987, blks_read=0, blks_hit=9105913, wal_records=3296438, wal_fpi=7168, wal_bytes=481970755, wal_buffers_full=0, wal_write=148635, wal_sync=148635, wal_write_time=983.5239999999999, wal_sync_time=44645.634}
Store operations, kafkaEndOffsets (seconds -29.799 to 62.122): {wow.transfer.account.state=564790}

PostgreSQL per second: commits 7181, WAL flushes 2437, WAL flush time 732 ms (max 961 ms), WAL write time 16 ms
