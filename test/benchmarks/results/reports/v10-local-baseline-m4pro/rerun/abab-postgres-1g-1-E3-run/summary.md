# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-postgres-1g-1-up.

Open loop at 1428.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1369.3/s | 82157 | 3523 | 17.5 ms | 1441.8 ms | 5046.3 ms | 6750.2 ms | 8253.4 ms | 7667.7 ms |

Errors: {http-429-wow.command.busy=3523}

Load generator: 0.37 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.87 | 1143 MB | 815 MB | 49 | 0.368 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.4% | 140.4% | 2.187GiB / 15.6GiB |
| wow-e2e-bench-postgres | 205.1% | 541.6% | 890.8MiB / 15.6GiB |

Store operations, postgres (seconds -0.068 to 60.933): {epoch=61.00116205215454, xact_commit=341021, xact_rollback=0, tup_inserted=248015, tup_updated=439126, tup_deleted=0, tup_fetched=6194487, tup_returned=6200760, blks_read=4875443, blks_hit=7696690, wal_records=2053108, wal_fpi=78235, wal_bytes=812539475, wal_buffers_full=0, wal_write=117117, wal_sync=117117, wal_write_time=1247.3950000000004, wal_sync_time=47283.09499999997}
Store operations, kafkaEndOffsets (seconds -30.011 to 62.229): {wow.transfer.account.state=372783}

PostgreSQL per second: commits 5590, WAL flushes 1920, WAL flush time 775 ms (max 2523 ms), WAL write time 20 ms
