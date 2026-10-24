# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 18, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-postgres-up.

Open loop at 1251.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1251.0/s | 75060 | 0 | 0.6 ms | 2.7 ms | 151.6 ms | 634.9 ms | 724.7 ms | 712.7 ms |

Load generator: 0.20 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.76 | 898 MB | 779 MB | 11 | 0.028 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.7% | 116.1% | 2.086GiB / 15.6GiB |
| wow-e2e-bench-postgres | 31.3% | 46.0% | 763.6MiB / 15.6GiB |

Store operations, postgres (seconds 0.0 to 60.001): {epoch=60.000300884246826, xact_commit=153592, xact_rollback=0, tup_inserted=75708, tup_updated=182603, tup_deleted=0, tup_fetched=406274, tup_returned=416888, blks_read=82313, blks_hit=1932229, wal_records=765824, wal_fpi=8282, wal_bytes=144145423, wal_buffers_full=0, wal_write=142970, wal_sync=142969, wal_write_time=624.273000000001, wal_sync_time=19264.109999999986}
Store operations, kafkaEndOffsets (seconds -29.987 to 61.954): {wow.transfer.account.state=118219}

PostgreSQL per second: commits 2560, WAL flushes 2383, WAL flush time 321 ms (max 974 ms), WAL write time 10 ms
