# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 4, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/3-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.2 ms | 8.1 ms | 31.4 ms | 235.5 ms | 264.2 ms | 258.0 ms |

Load generator: 0.15 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.90 | 1336 MB | 855 MB | 17 | 0.379 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 29.7% | 116.3% | 380.2MiB / 15.6GiB |
| wow-e2e-bench-postgres | 67.1% | 81.1% | 348.9MiB / 15.6GiB |

Store operations, postgres (seconds -0.053 to 40.952): {epoch=41.00520181655884, xact_commit=166933, xact_rollback=0, tup_inserted=112379, tup_updated=56337, tup_deleted=6, tup_fetched=662812, tup_returned=1005163, blks_read=0, blks_hit=3633812, wal_records=603546, wal_fpi=0, wal_bytes=89808440, wal_buffers_full=0, wal_write=76787, wal_sync=76787, wal_write_time=399.1959999999999, wal_sync_time=14460.888000000006}

PostgreSQL per second: commits 4071, WAL flushes 1873, WAL flush time 353 ms (max 750 ms), WAL write time 10 ms
