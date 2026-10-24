# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 18, settleSeconds 3, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-postgres-up.

Open loop at 665.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 665.0/s | 39900 | 0 | 3.9 ms | 1015.8 ms | 3407.9 ms | 3915.8 ms | 4079.6 ms | 4030.5 ms |

Load generator: 0.17 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.21 | 1045 MB | 831 MB | 20 | 0.248 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.0% | 156.9% | 2.281GiB / 15.6GiB |
| wow-e2e-bench-postgres | 113.8% | 326.7% | 854.8MiB / 15.6GiB |

Store operations, postgres (seconds -0.948 to 60.04): {epoch=60.988196849823, xact_commit=233557, xact_rollback=0, tup_inserted=122710, tup_updated=262353, tup_deleted=0, tup_fetched=2023957, tup_returned=2053796, blks_read=1347849, blks_hit=3985096, wal_records=1156067, wal_fpi=3365, wal_bytes=193835111, wal_buffers_full=0, wal_write=134808, wal_sync=134808, wal_write_time=1995.5499999999993, wal_sync_time=43279.625}
Store operations, kafkaEndOffsets (seconds -29.989 to 62.036): {wow.transfer.account.state=190582}

PostgreSQL per second: commits 3829, WAL flushes 2210, WAL flush time 710 ms (max 3571 ms), WAL write time 33 ms
