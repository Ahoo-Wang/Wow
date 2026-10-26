# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/4-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.6 ms | 0.9 ms | 3.0 ms | 12.0 ms | 18.1 ms | 9.3 ms |

Load generator: 0.38 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.39 | 1158 MB | 734 MB | 12 | 0.037 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 28.0% | 106.9% | 378.6MiB / 15.6GiB |
| wow-e2e-bench-postgres | 52.1% | 76.6% | 637.8MiB / 15.6GiB |

Store operations, postgres (seconds -0.058 to 40.946): {epoch=41.004454135894775, xact_commit=201251, xact_rollback=0, tup_inserted=101636, tup_updated=221914, tup_deleted=7, tup_fetched=284446, tup_returned=341398, blks_read=0, blks_hit=2514285, wal_records=1031111, wal_fpi=2, wal_bytes=123110228, wal_buffers_full=0, wal_write=196717, wal_sync=196717, wal_write_time=670.8530000000001, wal_sync_time=23171.957}

PostgreSQL per second: commits 4908, WAL flushes 4798, WAL flush time 565 ms (max 625 ms), WAL write time 16 ms
