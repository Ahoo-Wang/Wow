# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s0-up-1.

Open loop at 6346.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6340.6/s | 380435 | 325 | 43.8 ms | 286.7 ms | 663.6 ms | 1007.6 ms | 1550.0 ms | 1155.1 ms |

Errors: {http-429-wow.command.busy=325}

Load generator: 1.67 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.54 | 1175 MB | 821 MB | 80 | 0.449 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.3% | 173.1% | 2.324GiB / 15.6GiB |
| wow-e2e-bench-postgres | 256.7% | 385.3% | 1.032GiB / 15.6GiB |

Store operations, postgres (seconds -0.049 to 60.95): {epoch=60.998713970184326, xact_commit=352122, xact_rollback=0, tup_inserted=378384, tup_updated=568298, tup_deleted=0, tup_fetched=8923452, tup_returned=9019681, blks_read=6250826, blks_hit=12078885, wal_records=2838619, wal_fpi=49521, wal_bytes=701002005, wal_buffers_full=0, wal_write=91961, wal_sync=91961, wal_write_time=1057.2199999999993, wal_sync_time=40558.14299999998}
Store operations, kafkaEndOffsets (seconds -30.012 to 62.244): {wow.transfer.account.state=566301}

PostgreSQL per second: commits 5773, WAL flushes 1508, WAL flush time 665 ms (max 987 ms), WAL write time 17 ms
