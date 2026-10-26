# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 30, settleSeconds 3, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/3-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 2.0 ms | 40.7 ms | 108.5 ms | 128.0 ms | 329.7 ms | 197.6 ms |

Load generator: 0.52 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.41 | 1388 MB | 878 MB | 21 | 0.531 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.2% | 156.8% | 378.5MiB / 15.6GiB |
| wow-e2e-bench-postgres | 87.5% | 104.7% | 251.4MiB / 15.6GiB |

Store operations, postgres (seconds -0.063 to 40.942): {epoch=41.00507998466492, xact_commit=160227, xact_rollback=0, tup_inserted=100663, tup_updated=864, tup_deleted=6, tup_fetched=519310, tup_returned=631702, blks_read=0, blks_hit=3052019, wal_records=515171, wal_fpi=2, wal_bytes=64636443, wal_buffers_full=0, wal_write=89399, wal_sync=89399, wal_write_time=616.982, wal_sync_time=27016.21100000001}

PostgreSQL per second: commits 3908, WAL flushes 2180, WAL flush time 659 ms (max 813 ms), WAL write time 15 ms
