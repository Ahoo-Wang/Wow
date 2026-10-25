# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-091458-run-postgres-n1-saturation.

Open loop at 1775.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1775.0/s | 106500 | 0 | 8.8 ms | 309.2 ms | 688.1 ms | 876.5 ms | 945.5 ms | 905.2 ms |

Load generator: 0.36 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.54 | 994 MB | 843 MB | 39 | 0.162 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.1% | 152.8% | 2.215GiB / 15.6GiB |
| wow-e2e-bench-postgres | 101.2% | 139.0% | 1.101GiB / 15.6GiB |

Store operations, postgres (seconds -0.064 to 60.937): {epoch=61.00050091743469, xact_commit=184317, xact_rollback=0, tup_inserted=320961, tup_updated=620509, tup_deleted=0, tup_fetched=1281290, tup_returned=1287801, blks_read=270937, blks_hit=9806811, wal_records=2760167, wal_fpi=74044, wal_bytes=668800260, wal_buffers_full=0, wal_write=98323, wal_sync=98323, wal_write_time=1597.0480000000025, wal_sync_time=45548.60000000009}
Store operations, kafkaEndOffsets (seconds -30.018 to 62.034): {wow.transfer.account.state=485562}

PostgreSQL per second: commits 3021, WAL flushes 1612, WAL flush time 747 ms (max 1171 ms), WAL write time 26 ms
