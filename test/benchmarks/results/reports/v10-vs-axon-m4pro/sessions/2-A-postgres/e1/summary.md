# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 3, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/2-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.4 ms | 15.0 ms | 540.7 ms | 720.9 ms | 1205.2 ms | 766.0 ms |

Load generator: 0.51 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.37 | 1095 MB | 819 MB | 22 | 0.492 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.4% | 128.7% | 510.2MiB / 15.6GiB |
| wow-e2e-bench-postgres | 71.2% | 96.0% | 251MiB / 15.6GiB |

Store operations, postgres (seconds -0.983 to 40.022): {epoch=41.00556182861328, xact_commit=189004, xact_rollback=0, tup_inserted=103949, tup_updated=674, tup_deleted=6, tup_fetched=596009, tup_returned=692083, blks_read=0, blks_hit=3526784, wal_records=531875, wal_fpi=2, wal_bytes=66939487, wal_buffers_full=0, wal_write=99778, wal_sync=99778, wal_write_time=438.24, wal_sync_time=18522.004}

PostgreSQL per second: commits 4609, WAL flushes 2433, WAL flush time 452 ms (max 1133 ms), WAL write time 11 ms
