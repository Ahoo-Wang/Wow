# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 18, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-postgres-up.

Open loop at 1330.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1323.0/s | 79380 | 420 | 67.6 ms | 1261.6 ms | 2932.7 ms | 3473.4 ms | 4160.1 ms | 3686.4 ms |

Errors: {http-429-wow.command.busy=420}

Load generator: 0.45 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.81 | 1190 MB | 896 MB | 45 | 0.596 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.0% | 159.6% | 2.164GiB / 15.6GiB |
| wow-e2e-bench-postgres | 206.8% | 379.8% | 1.043GiB / 15.6GiB |

Store operations, postgres (seconds -0.083 to 60.917): {epoch=60.99949407577515, xact_commit=253732, xact_rollback=0, tup_inserted=239396, tup_updated=416044, tup_deleted=0, tup_fetched=4239030, tup_returned=4258305, blks_read=2962326, blks_hit=7262167, wal_records=1925989, wal_fpi=41658, wal_bytes=558739196, wal_buffers_full=0, wal_write=72617, wal_sync=72617, wal_write_time=3597.1090000000004, wal_sync_time=44876.54700000002}
Store operations, kafkaEndOffsets (seconds -30.051 to 62.244): {wow.transfer.account.state=365416}

PostgreSQL per second: commits 4160, WAL flushes 1190, WAL flush time 736 ms (max 1596 ms), WAL write time 59 ms
