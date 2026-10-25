# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-091458-run-postgres-n1-saturation.

Open loop at 5251.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4951.1/s | 297068 | 17992 | 6.1 ms | 428.0 ms | 2998.3 ms | 3538.9 ms | 3679.5 ms | 3637.2 ms |

Errors: {http-429-wow.command.busy=17992}

Load generator: 1.21 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.58 | 1135 MB | 861 MB | 48 | 0.242 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.4% | 120.8% | 2.298GiB / 15.6GiB |
| wow-e2e-bench-postgres | 93.3% | 215.1% | 1.069GiB / 15.6GiB |

Store operations, postgres (seconds -0.962 to 60.038): {epoch=60.99931812286377, xact_commit=140472, xact_rollback=0, tup_inserted=302896, tup_updated=583016, tup_deleted=11, tup_fetched=1203152, tup_returned=2429501, blks_read=90676, blks_hit=10786793, wal_records=2605865, wal_fpi=50, wal_bytes=371406701, wal_buffers_full=0, wal_write=69175, wal_sync=69175, wal_write_time=1121.2999999999993, wal_sync_time=45240.70200000005}
Store operations, kafkaEndOffsets (seconds -29.747 to 62.132): {wow.transfer.account.state=456641}

PostgreSQL per second: commits 2302, WAL flushes 1134, WAL flush time 742 ms (max 2183 ms), WAL write time 18 ms
