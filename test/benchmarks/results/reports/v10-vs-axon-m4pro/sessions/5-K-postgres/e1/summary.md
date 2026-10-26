# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/5-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.6 ms | 1.0 ms | 2.7 ms | 15.9 ms | 43.1 ms | 34.8 ms |

Load generator: 0.41 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.73 | 1201 MB | 752 MB | 14 | 0.041 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.3% | 128.3% | 1.729GiB / 15.6GiB |
| wow-e2e-bench-postgres | 56.1% | 76.0% | 642MiB / 15.6GiB |

Store operations, postgres (seconds -0.056 to 40.949): {epoch=41.00471901893616, xact_commit=202156, xact_rollback=0, tup_inserted=102040, tup_updated=226085, tup_deleted=7, tup_fetched=289113, tup_returned=345617, blks_read=0, blks_hit=2541029, wal_records=1041650, wal_fpi=2, wal_bytes=124170592, wal_buffers_full=0, wal_write=194478, wal_sync=194478, wal_write_time=702.23, wal_sync_time=25096.527000000002}
Store operations, kafkaEndOffsets (seconds -19.855 to 41.818): {wow.transfer.account.state=148055}

PostgreSQL per second: commits 4930, WAL flushes 4743, WAL flush time 612 ms (max 670 ms), WAL write time 17 ms
