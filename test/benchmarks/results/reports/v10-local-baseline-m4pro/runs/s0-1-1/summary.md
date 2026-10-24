# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s0-up-1.

Open loop at 7038.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7038.0/s | 422280 | 0 | 3.8 ms | 15.3 ms | 57.6 ms | 227.3 ms | 307.0 ms | 270.3 ms |

Load generator: 1.86 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.30 | 1303 MB | 801 MB | 58 | 0.276 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.2% | 135.6% | 1.984GiB / 15.6GiB |
| wow-e2e-bench-postgres | 138.4% | 183.4% | 1.06GiB / 15.6GiB |

Store operations, postgres (seconds -0.057 to 60.945): {epoch=61.0025429725647, xact_commit=483785, xact_rollback=0, tup_inserted=423282, tup_updated=842907, tup_deleted=7, tup_fetched=1284819, tup_returned=1392788, blks_read=57, blks_hit=10182843, wal_records=3685568, wal_fpi=2822, wal_bytes=508060361, wal_buffers_full=0, wal_write=163583, wal_sync=163583, wal_write_time=1019.711, wal_sync_time=49376.911}
Store operations, kafkaEndOffsets (seconds -29.688 to 61.999): {wow.transfer.account.state=626017}

PostgreSQL per second: commits 7931, WAL flushes 2682, WAL flush time 809 ms (max 896 ms), WAL write time 17 ms
