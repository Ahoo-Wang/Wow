# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 6, settleSeconds 4, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/1-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.6 ms | 1.4 ms | 3.2 ms | 10.8 ms | 25.9 ms | 12.2 ms |

Load generator: 0.42 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.24 | 1159 MB | 725 MB | 12 | 0.036 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 33.0% | 128.1% | 378.9MiB / 15.6GiB |
| wow-e2e-bench-postgres | 58.0% | 83.1% | 636.1MiB / 15.6GiB |

Store operations, postgres (seconds -0.061 to 40.94): {epoch=41.001574993133545, xact_commit=198426, xact_rollback=0, tup_inserted=101063, tup_updated=221901, tup_deleted=7, tup_fetched=287760, tup_returned=343354, blks_read=0, blks_hit=2517569, wal_records=1028456, wal_fpi=2, wal_bytes=122807201, wal_buffers_full=0, wal_write=179537, wal_sync=179537, wal_write_time=626.707, wal_sync_time=27340.795000000002}

PostgreSQL per second: commits 4839, WAL flushes 4379, WAL flush time 667 ms (max 764 ms), WAL write time 15 ms
