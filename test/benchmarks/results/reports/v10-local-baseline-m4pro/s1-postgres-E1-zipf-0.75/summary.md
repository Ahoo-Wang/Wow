# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-postgres-up.

Open loop at 3384.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3224.4/s | 193464 | 9576 | 1.7 ms | 643.1 ms | 4096.0 ms | 7372.8 ms | 7418.9 ms | 7405.6 ms |

Errors: {http-429-wow.command.busy=9576}

Load generator: 0.68 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.71 | 1197 MB | 839 MB | 34 | 0.143 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 33.6% | 123.4% | 2.297GiB / 15.6GiB |
| wow-e2e-bench-postgres | 86.7% | 160.3% | 858.9MiB / 15.6GiB |

Store operations, postgres (seconds -0.057 to 60.944): {epoch=61.00019693374634, xact_commit=315689, xact_rollback=0, tup_inserted=194885, tup_updated=409189, tup_deleted=9, tup_fetched=972811, tup_returned=1044066, blks_read=292551, blks_hit=4826599, wal_records=1829822, wal_fpi=16320, wal_bytes=329677412, wal_buffers_full=0, wal_write=157826, wal_sync=157826, wal_write_time=945.518, wal_sync_time=50926.97399999993}
Store operations, kafkaEndOffsets (seconds -30.033 to 62.006): {wow.transfer.account.state=267837}

PostgreSQL per second: commits 5175, WAL flushes 2587, WAL flush time 835 ms (max 3532 ms), WAL write time 16 ms
