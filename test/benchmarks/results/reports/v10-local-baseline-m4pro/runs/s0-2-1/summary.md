# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s0-up-2.

Open loop at 7038.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7038.0/s | 422280 | 0 | 8.0 ms | 103.4 ms | 376.8 ms | 618.5 ms | 777.1 ms | 679.9 ms |

Load generator: 1.81 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.92 | 1279 MB | 582 MB | 61 | 0.327 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.4% | 106.6% | 2.031GiB / 15.6GiB |
| wow-e2e-bench-postgres | 147.1% | 261.6% | 1.003GiB / 15.6GiB |

Store operations, postgres (seconds -0.059 to 60.945): {epoch=61.00380897521973, xact_commit=385058, xact_rollback=0, tup_inserted=423497, tup_updated=771189, tup_deleted=7, tup_fetched=1213977, tup_returned=1316430, blks_read=135, blks_hit=9967334, wal_records=3517796, wal_fpi=2533, wal_bytes=499933471, wal_buffers_full=0, wal_write=132270, wal_sync=132270, wal_write_time=1036.0510000000002, wal_sync_time=46568.995}
Store operations, kafkaEndOffsets (seconds -29.767 to 62.053): {wow.transfer.account.state=625711}

PostgreSQL per second: commits 6312, WAL flushes 2168, WAL flush time 763 ms (max 1060 ms), WAL write time 17 ms
