# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s0-up-3.

Open loop at 6346.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6335.4/s | 380123 | 637 | 4.1 ms | 17.5 ms | 247.8 ms | 757.8 ms | 928.0 ms | 831.5 ms |

Errors: {http-429-wow.command.busy=637}

Load generator: 1.82 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.97 | 1269 MB | 830 MB | 52 | 0.208 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.1% | 143.1% | 2.089GiB / 15.6GiB |
| wow-e2e-bench-postgres | 126.9% | 179.0% | 991.1MiB / 15.6GiB |

Store operations, postgres (seconds -0.052 to 60.95): {epoch=61.00230813026428, xact_commit=451308, xact_rollback=0, tup_inserted=381948, tup_updated=767251, tup_deleted=7, tup_fetched=1141229, tup_returned=1309781, blks_read=0, blks_hit=9291233, wal_records=3352945, wal_fpi=7097, wal_bytes=488669285, wal_buffers_full=0, wal_write=153982, wal_sync=153982, wal_write_time=1056.2350000000001, wal_sync_time=44466.61000000001}
Store operations, kafkaEndOffsets (seconds -29.739 to 62.015): {wow.transfer.account.state=561688}

PostgreSQL per second: commits 7398, WAL flushes 2524, WAL flush time 729 ms (max 1190 ms), WAL write time 17 ms
