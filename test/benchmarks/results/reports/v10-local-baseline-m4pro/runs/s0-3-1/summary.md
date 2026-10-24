# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s0-up-3.

Open loop at 7038.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7038.0/s | 422280 | 0 | 7.6 ms | 22.1 ms | 76.3 ms | 255.0 ms | 348.0 ms | 292.9 ms |

Load generator: 1.93 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.27 | 1130 MB | 634 MB | 97 | 0.270 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.6% | 115.0% | 2.014GiB / 15.6GiB |
| wow-e2e-bench-postgres | 132.2% | 188.1% | 1.097GiB / 15.6GiB |

Store operations, postgres (seconds -0.062 to 60.937): {epoch=60.999022006988525, xact_commit=425993, xact_rollback=0, tup_inserted=423473, tup_updated=814815, tup_deleted=7, tup_fetched=1257565, tup_returned=1363247, blks_read=110, blks_hit=10102334, wal_records=3600137, wal_fpi=4309, wal_bytes=514388352, wal_buffers_full=0, wal_write=143470, wal_sync=143470, wal_write_time=1034.5880000000002, wal_sync_time=43691.147}
Store operations, kafkaEndOffsets (seconds -29.691 to 62.005): {wow.transfer.account.state=623872}

PostgreSQL per second: commits 6984, WAL flushes 2352, WAL flush time 716 ms (max 913 ms), WAL write time 17 ms
