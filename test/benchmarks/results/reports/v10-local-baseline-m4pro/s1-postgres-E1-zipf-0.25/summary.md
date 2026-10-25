# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-postgres-up.

Open loop at 1128.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1128.0/s | 67680 | 0 | 0.8 ms | 45.8 ms | 518.1 ms | 761.9 ms | 845.3 ms | 823.3 ms |

Load generator: 0.19 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.66 | 908 MB | 843 MB | 11 | 0.036 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.6% | 108.7% | 2.161GiB / 15.6GiB |
| wow-e2e-bench-postgres | 31.3% | 69.7% | 833.5MiB / 15.6GiB |

Store operations, postgres (seconds -0.892 to 60.109): {epoch=61.00082206726074, xact_commit=134622, xact_rollback=0, tup_inserted=68180, tup_updated=167011, tup_deleted=0, tup_fetched=425426, tup_returned=435980, blks_read=152648, blks_hit=1766023, wal_records=688712, wal_fpi=2490, wal_bytes=97366213, wal_buffers_full=0, wal_write=116276, wal_sync=116276, wal_write_time=607.9549999999981, wal_sync_time=24991.996000000043}
Store operations, kafkaEndOffsets (seconds -29.934 to 62.069): {wow.transfer.account.state=114833}

PostgreSQL per second: commits 2207, WAL flushes 1906, WAL flush time 410 ms (max 1198 ms), WAL write time 10 ms
