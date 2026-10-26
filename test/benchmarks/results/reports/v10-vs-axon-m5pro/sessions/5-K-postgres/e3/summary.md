# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/5-K-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.0 ms | 10.5 ms | 22.4 ms | 36.4 ms | 56.1 ms | 41.2 ms |

Load generator: 0.14 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.12 | 1243 MB | 632 MB | 9 | 0.025 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.8% | 134.7% | 1.849GiB / 15.6GiB |
| wow-e2e-bench-postgres | 45.1% | 78.6% | 757.7MiB / 15.6GiB |

Store operations, postgres (seconds -0.05 to 40.94): {epoch=40.98994779586792, xact_commit=162576, xact_rollback=0, tup_inserted=84491, tup_updated=190248, tup_deleted=7, tup_fetched=239739, tup_returned=285792, blks_read=0, blks_hit=2259482, wal_records=852592, wal_fpi=0, wal_bytes=124915621, wal_buffers_full=0, wal_write=154732, wal_sync=154732, wal_write_time=472.75300000000016, wal_sync_time=22405.016000000003}
Store operations, kafkaEndOffsets (seconds -20.036 to 41.782): {wow.transfer.account.state=124629}

PostgreSQL per second: commits 3966, WAL flushes 3775, WAL flush time 547 ms (max 876 ms), WAL write time 12 ms
