# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 18, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-postgres-up.

Open loop at 3452.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3452.0/s | 207119 | 0 | 1.1 ms | 4.3 ms | 29.2 ms | 148.5 ms | 207.3 ms | 178.2 ms |

Load generator: 0.79 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.51 | 1082 MB | 826 MB | 30 | 0.152 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.8% | 137.8% | 2.011GiB / 15.6GiB |
| wow-e2e-bench-postgres | 102.8% | 134.7% | 791.4MiB / 15.6GiB |

Store operations, postgres (seconds -0.004 to 59.995): {epoch=59.99902319908142, xact_commit=404469, xact_rollback=0, tup_inserted=207272, tup_updated=444054, tup_deleted=9, tup_fetched=1009243, tup_returned=1099925, blks_read=134509, blks_hit=5622762, wal_records=2003358, wal_fpi=142, wal_bytes=248121219, wal_buffers_full=0, wal_write=217961, wal_sync=217961, wal_write_time=1151.6949999999997, wal_sync_time=48653.00199999998}
Store operations, kafkaEndOffsets (seconds -29.852 to 61.981): {wow.transfer.account.state=307568}

PostgreSQL per second: commits 6741, WAL flushes 3633, WAL flush time 811 ms (max 927 ms), WAL write time 19 ms
