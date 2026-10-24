# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s0-up-3.

Open loop at 7038.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7038.0/s | 422280 | 0 | 7.2 ms | 22.4 ms | 79.4 ms | 276.5 ms | 348.7 ms | 307.2 ms |

Load generator: 1.91 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.53 | 988 MB | 753 MB | 78 | 0.269 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.5% | 132.5% | 2.012GiB / 15.6GiB |
| wow-e2e-bench-postgres | 144.9% | 195.1% | 1.117GiB / 15.6GiB |

Store operations, postgres (seconds -0.055 to 60.946): {epoch=61.000473976135254, xact_commit=462326, xact_rollback=0, tup_inserted=423506, tup_updated=816467, tup_deleted=7, tup_fetched=1787484, tup_returned=1895205, blks_read=131833, blks_hit=10863787, wal_records=3601379, wal_fpi=12400, wal_bytes=556969503, wal_buffers_full=0, wal_write=148566, wal_sync=148566, wal_write_time=1282.1929999999998, wal_sync_time=47780.793999999994}
Store operations, kafkaEndOffsets (seconds -30.021 to 62.014): {wow.transfer.account.state=628537}

PostgreSQL per second: commits 7579, WAL flushes 2436, WAL flush time 783 ms (max 904 ms), WAL write time 21 ms
