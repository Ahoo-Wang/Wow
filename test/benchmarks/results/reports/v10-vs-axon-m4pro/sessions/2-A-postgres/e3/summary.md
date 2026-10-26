# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 3, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/2-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.0 ms | 4.5 ms | 21.1 ms | 61.7 ms | 89.1 ms | 77.3 ms |

Load generator: 0.15 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.93 | 1082 MB | 806 MB | 19 | 0.390 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.0% | 117.4% | 517.3MiB / 15.6GiB |
| wow-e2e-bench-postgres | 70.1% | 94.2% | 348.2MiB / 15.6GiB |

Store operations, postgres (seconds -0.047 to 40.956): {epoch=41.00310397148132, xact_commit=169862, xact_rollback=0, tup_inserted=112059, tup_updated=56165, tup_deleted=6, tup_fetched=671301, tup_returned=950455, blks_read=0, blks_hit=3629153, wal_records=601856, wal_fpi=0, wal_bytes=89825864, wal_buffers_full=0, wal_write=80540, wal_sync=80540, wal_write_time=384.586, wal_sync_time=11860.572999999997}

PostgreSQL per second: commits 4143, WAL flushes 1964, WAL flush time 289 ms (max 399 ms), WAL write time 9 ms
