# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 4, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/2-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.3 ms | 5.9 ms | 18.7 ms | 36.1 ms | 45.5 ms | 35.6 ms |

Load generator: 0.15 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.92 | 1417 MB | 834 MB | 19 | 0.413 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 33.7% | 135.1% | 401.7MiB / 15.6GiB |
| wow-e2e-bench-postgres | 69.6% | 83.3% | 354.3MiB / 15.6GiB |

Store operations, postgres (seconds -0.058 to 40.945): {epoch=41.002630949020386, xact_commit=163988, xact_rollback=0, tup_inserted=112067, tup_updated=56257, tup_deleted=6, tup_fetched=657352, tup_returned=868810, blks_read=0, blks_hit=3511732, wal_records=601857, wal_fpi=0, wal_bytes=89419729, wal_buffers_full=0, wal_write=78836, wal_sync=78836, wal_write_time=441.50299999999993, wal_sync_time=15280.133999999991}

PostgreSQL per second: commits 4000, WAL flushes 1923, WAL flush time 373 ms (max 476 ms), WAL write time 11 ms
