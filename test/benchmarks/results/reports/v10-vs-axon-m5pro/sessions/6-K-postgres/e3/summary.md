# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/6-K-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.0 ms | 2.1 ms | 3.1 ms | 12.8 ms | 26.8 ms | 18.2 ms |

Load generator: 0.13 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.09 | 1227 MB | 677 MB | 9 | 0.024 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.2% | 130.9% | 1.835GiB / 15.6GiB |
| wow-e2e-bench-postgres | 46.4% | 82.0% | 752.7MiB / 15.6GiB |

Store operations, postgres (seconds -0.058 to 40.941): {epoch=40.99885296821594, xact_commit=169929, xact_rollback=0, tup_inserted=85026, tup_updated=191986, tup_deleted=7, tup_fetched=237546, tup_returned=283715, blks_read=0, blks_hit=2266861, wal_records=860660, wal_fpi=0, wal_bytes=125666273, wal_buffers_full=0, wal_write=163473, wal_sync=163473, wal_write_time=528.192, wal_sync_time=21403.810999999987}
Store operations, kafkaEndOffsets (seconds -20.037 to 41.766): {wow.transfer.account.state=124584}

PostgreSQL per second: commits 4145, WAL flushes 3987, WAL flush time 522 ms (max 592 ms), WAL write time 13 ms
