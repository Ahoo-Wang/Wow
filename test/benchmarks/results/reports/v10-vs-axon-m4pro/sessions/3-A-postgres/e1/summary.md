# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 3, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/3-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.3 ms | 3.5 ms | 74.8 ms | 259.1 ms | 407.7 ms | 272.4 ms |

Load generator: 0.45 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.36 | 1397 MB | 862 MB | 22 | 0.494 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 28.5% | 105.6% | 378.7MiB / 15.6GiB |
| wow-e2e-bench-postgres | 71.1% | 89.5% | 251.2MiB / 15.6GiB |

Store operations, postgres (seconds -0.057 to 40.95): {epoch=41.006300926208496, xact_commit=188657, xact_rollback=0, tup_inserted=102031, tup_updated=675, tup_deleted=6, tup_fetched=594507, tup_returned=691720, blks_read=0, blks_hit=3518229, wal_records=521982, wal_fpi=2, wal_bytes=65481895, wal_buffers_full=0, wal_write=99229, wal_sync=99229, wal_write_time=485.09799999999996, wal_sync_time=17620.222999999998}

PostgreSQL per second: commits 4601, WAL flushes 2420, WAL flush time 430 ms (max 652 ms), WAL write time 12 ms
