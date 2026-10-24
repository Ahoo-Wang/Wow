# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 18, settleSeconds 3, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-postgres-up.

Open loop at 6904.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6904.0/s | 414239 | 0 | 28.9 ms | 106.5 ms | 237.6 ms | 364.5 ms | 455.3 ms | 393.2 ms |

Load generator: 1.79 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.94 | 1117 MB | 819 MB | 67 | 0.398 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.1% | 162.0% | 2.038GiB / 15.6GiB |
| wow-e2e-bench-postgres | 198.5% | 267.0% | 1.1GiB / 15.6GiB |

Store operations, postgres (seconds -0.058 to 60.945): {epoch=61.00275993347168, xact_commit=325006, xact_rollback=0, tup_inserted=415259, tup_updated=660071, tup_deleted=9, tup_fetched=2838868, tup_returned=2900976, blks_read=990578, blks_hit=11272993, wal_records=3238684, wal_fpi=18788, wal_bytes=580138328, wal_buffers_full=0, wal_write=100185, wal_sync=100185, wal_write_time=1149.839, wal_sync_time=44397.42499999999}
Store operations, kafkaEndOffsets (seconds -30.026 to 62.141): {wow.transfer.account.state=615837}

PostgreSQL per second: commits 5328, WAL flushes 1642, WAL flush time 728 ms (max 812 ms), WAL write time 19 ms
