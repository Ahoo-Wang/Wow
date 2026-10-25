# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-225058-run-postgres-n1-saturation.

Open loop at 5301.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4895.2/s | 293710 | 24350 | 27.1 ms | 1204.2 ms | 2097.2 ms | 2572.3 ms | 4279.3 ms | 2818.0 ms |

Errors: {http-429-wow.command.busy=24350}

Load generator: 1.36 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.45 | 1149 MB | 868 MB | 89 | 0.456 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.3% | 173.9% | 2.138GiB / 15.6GiB |
| wow-e2e-bench-postgres | 236.5% | 414.5% | 1.222GiB / 15.6GiB |

Store operations, postgres (seconds -0.941 to 60.042): {epoch=60.9828040599823, xact_commit=301797, xact_rollback=0, tup_inserted=300984, tup_updated=503804, tup_deleted=11, tup_fetched=7003662, tup_returned=7010402, blks_read=5550665, blks_hit=9877526, wal_records=2339509, wal_fpi=18, wal_bytes=345197266, wal_buffers_full=0, wal_write=93603, wal_sync=93603, wal_write_time=749.4069999999992, wal_sync_time=39731.707999999984}
Store operations, kafkaEndOffsets (seconds -29.735 to 62.234): {wow.transfer.account.state=443376}

PostgreSQL per second: commits 4949, WAL flushes 1535, WAL flush time 652 ms (max 1640 ms), WAL write time 12 ms
