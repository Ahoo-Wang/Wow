# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s0-up-1.

Open loop at 7038.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7038.0/s | 422280 | 0 | 4.7 ms | 16.0 ms | 71.2 ms | 225.3 ms | 296.1 ms | 266.2 ms |

Load generator: 1.85 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.33 | 1133 MB | 686 MB | 61 | 0.278 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.9% | 142.9% | 2.005GiB / 15.6GiB |
| wow-e2e-bench-postgres | 145.0% | 194.2% | 1.095GiB / 15.6GiB |

Store operations, postgres (seconds -0.061 to 60.941): {epoch=61.00213599205017, xact_commit=504366, xact_rollback=0, tup_inserted=423775, tup_updated=839003, tup_deleted=7, tup_fetched=1808849, tup_returned=1916129, blks_read=131810, blks_hit=10931763, wal_records=3660655, wal_fpi=12399, wal_bytes=557770443, wal_buffers_full=0, wal_write=163073, wal_sync=163073, wal_write_time=1363.0169999999998, wal_sync_time=48810.28599999999}
Store operations, kafkaEndOffsets (seconds -30.028 to 61.966): {wow.transfer.account.state=627026}

PostgreSQL per second: commits 8268, WAL flushes 2673, WAL flush time 800 ms (max 887 ms), WAL write time 22 ms
