# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/4-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.6 ms | 1.3 ms | 2.0 ms | 7.4 ms | 16.6 ms | 8.0 ms |

Load generator: 0.42 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.25 | 1138 MB | 717 MB | 19 | 0.036 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 32.8% | 133.0% | 381MiB / 15.6GiB |
| wow-e2e-bench-postgres | 57.1% | 96.2% | 636.4MiB / 15.6GiB |

Store operations, postgres (seconds -0.063 to 40.937): {epoch=41.000776052474976, xact_commit=200214, xact_rollback=0, tup_inserted=101206, tup_updated=222455, tup_deleted=7, tup_fetched=284011, tup_returned=341921, blks_read=0, blks_hit=2514426, wal_records=1030685, wal_fpi=3, wal_bytes=123041888, wal_buffers_full=0, wal_write=182979, wal_sync=182979, wal_write_time=610.7549999999999, wal_sync_time=27017.667999999998}

PostgreSQL per second: commits 4883, WAL flushes 4463, WAL flush time 659 ms (max 750 ms), WAL write time 15 ms
