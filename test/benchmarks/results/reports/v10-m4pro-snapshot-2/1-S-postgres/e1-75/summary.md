# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-091458-run-postgres-n1-saturation.

Open loop at 15754.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 14449.1/s | 866945 | 78295 | 245.8 ms | 409.6 ms | 778.2 ms | 1261.6 ms | 1642.7 ms | 1351.7 ms |

Errors: {http-429-wow.command.busy=78295}

Load generator: 2.71 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.73 | 1150 MB | 870 MB | 183 | 1.106 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.0% | 161.9% | 2.23GiB / 15.6GiB |
| wow-e2e-bench-postgres | 186.8% | 240.5% | 1.007GiB / 15.6GiB |

Store operations, postgres (seconds -0.053 to 60.946): {epoch=60.99880909919739, xact_commit=42028, xact_rollback=0, tup_inserted=864219, tup_updated=1011353, tup_deleted=0, tup_fetched=2087173, tup_returned=2093890, blks_read=305583, blks_hit=21058137, wal_records=5700487, wal_fpi=59, wal_bytes=939852164, wal_buffers_full=0, wal_write=29481, wal_sync=29481, wal_write_time=1437.8449999999975, wal_sync_time=23700.525000000023}
Store operations, kafkaEndOffsets (seconds -30.0 to 62.398): {wow.transfer.account.state=1304726}

PostgreSQL per second: commits 689, WAL flushes 483, WAL flush time 389 ms (max 1111 ms), WAL write time 24 ms
