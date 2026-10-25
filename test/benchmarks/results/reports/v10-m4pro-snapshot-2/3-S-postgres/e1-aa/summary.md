# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-101353-run-postgres-n1-saturation.

Open loop at 10503.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10491.4/s | 629485 | 695 | 4.6 ms | 51.7 ms | 179.2 ms | 481.3 ms | 633.8 ms | 565.2 ms |

Errors: {http-429-wow.command.busy=695}

Load generator: 2.60 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.32 | 1183 MB | 811 MB | 85 | 0.397 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.1% | 134.5% | 1.994GiB / 15.6GiB |
| wow-e2e-bench-postgres | 129.9% | 169.1% | 911MiB / 15.6GiB |

Store operations, postgres (seconds -0.942 to 60.058): {epoch=61.00040316581726, xact_commit=214531, xact_rollback=0, tup_inserted=635623, tup_updated=1205128, tup_deleted=7, tup_fetched=2279907, tup_returned=5071906, blks_read=259, blks_hit=18707626, wal_records=5507596, wal_fpi=2, wal_bytes=745217374, wal_buffers_full=0, wal_write=133507, wal_sync=133507, wal_write_time=1143.6490000000001, wal_sync_time=39654.953}
Store operations, kafkaEndOffsets (seconds -29.737 to 62.13): {wow.transfer.account.state=932876}

PostgreSQL per second: commits 3517, WAL flushes 2189, WAL flush time 650 ms (max 746 ms), WAL write time 19 ms
