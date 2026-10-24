# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/heap2g-runs/heap2g-postgres-up.

Open loop at 6904.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6904.0/s | 414239 | 0 | 2.5 ms | 9.2 ms | 32.1 ms | 162.8 ms | 232.2 ms | 203.8 ms |

Load generator: 1.85 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.36 | 1831 MB | 1468 MB | 27 | 0.159 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.5% | 135.1% | 2.115GiB / 15.6GiB |
| wow-e2e-bench-postgres | 130.1% | 165.4% | 1.049GiB / 15.6GiB |

Store operations, postgres (seconds -0.997 to 60.004): {epoch=61.00080490112305, xact_commit=502264, xact_rollback=0, tup_inserted=422291, tup_updated=856922, tup_deleted=9, tup_fetched=1187032, tup_returned=1297506, blks_read=17857, blks_hit=9993907, wal_records=3747528, wal_fpi=15433, wal_bytes=589482294, wal_buffers_full=0, wal_write=174519, wal_sync=174519, wal_write_time=1219.478, wal_sync_time=50871.504000000015}
Store operations, kafkaEndOffsets (seconds -29.796 to 61.99): {wow.transfer.account.state=614693}

PostgreSQL per second: commits 8234, WAL flushes 2861, WAL flush time 834 ms (max 985 ms), WAL write time 20 ms
