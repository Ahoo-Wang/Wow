# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 12, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-234759-run-postgres-n1-saturation.

Open loop at 10602.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10602.0/s | 636120 | 0 | 28.7 ms | 65.3 ms | 121.9 ms | 230.4 ms | 399.8 ms | 290.8 ms |

Load generator: 2.38 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.17 | 1112 MB | 855 MB | 95 | 0.526 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.2% | 157.7% | 2.027GiB / 15.6GiB |
| wow-e2e-bench-postgres | 263.5% | 334.0% | 927.3MiB / 15.6GiB |

Store operations, postgres (seconds -0.002 to 60.005): {epoch=60.006596088409424, xact_commit=221004, xact_rollback=0, tup_inserted=626657, tup_updated=865643, tup_deleted=7, tup_fetched=1408206, tup_returned=1501800, blks_read=10498, blks_hit=13152573, wal_records=4586890, wal_fpi=3, wal_bytes=695001928, wal_buffers_full=0, wal_write=98411, wal_sync=98411, wal_write_time=1178.097, wal_sync_time=48430.89800000001}
Store operations, kafkaEndOffsets (seconds -29.687 to 62.256): {wow.transfer.account.state=942570}

PostgreSQL per second: commits 3683, WAL flushes 1640, WAL flush time 807 ms (max 876 ms), WAL write time 20 ms
