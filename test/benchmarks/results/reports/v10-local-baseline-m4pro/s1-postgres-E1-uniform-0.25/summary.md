# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-postgres-up.

Open loop at 2808.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2804.3/s | 168257 | 223 | 2.2 ms | 107.5 ms | 1097.7 ms | 1622.0 ms | 1787.3 ms | 1703.9 ms |

Errors: {http-429-wow.command.busy=223}

Load generator: 0.76 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.62 | 1103 MB | 827 MB | 30 | 0.109 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.4% | 139.0% | 2.062GiB / 15.6GiB |
| wow-e2e-bench-postgres | 101.9% | 146.0% | 824.6MiB / 15.6GiB |

Store operations, postgres (seconds -0.949 to 60.049): {epoch=60.99782586097717, xact_commit=322588, xact_rollback=0, tup_inserted=172715, tup_updated=360000, tup_deleted=0, tup_fetched=2353850, tup_returned=2398386, blks_read=1318411, blks_hit=5326069, wal_records=1601801, wal_fpi=508, wal_bytes=208600490, wal_buffers_full=0, wal_write=133102, wal_sync=133102, wal_write_time=851.244999999999, wal_sync_time=49974.56099999999}
Store operations, kafkaEndOffsets (seconds -29.736 to 62.042): {wow.transfer.account.state=240447}

PostgreSQL per second: commits 5288, WAL flushes 2182, WAL flush time 819 ms (max 3223 ms), WAL write time 14 ms
