# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 3, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s0-up-2.

Open loop at 7038.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6889.1/s | 413346 | 8934 | 12.0 ms | 133.1 ms | 725.0 ms | 1867.8 ms | 2000.3 ms | 1966.1 ms |

Errors: {http-429-wow.command.busy=8934}

Load generator: 1.81 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.76 | 1243 MB | 835 MB | 62 | 0.337 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.0% | 136.3% | 2.008GiB / 15.6GiB |
| wow-e2e-bench-postgres | 144.6% | 185.0% | 1.087GiB / 15.6GiB |

Store operations, postgres (seconds -0.054 to 60.947): {epoch=61.00087904930115, xact_commit=379292, xact_rollback=0, tup_inserted=413402, tup_updated=734558, tup_deleted=7, tup_fetched=1669472, tup_returned=1774813, blks_read=125816, blks_hit=10413028, wal_records=3380337, wal_fpi=12540, wal_bytes=541346513, wal_buffers_full=0, wal_write=121829, wal_sync=121829, wal_write_time=1140.7849999999999, wal_sync_time=45618.406}
Store operations, kafkaEndOffsets (seconds -30.026 to 62.058): {wow.transfer.account.state=619480}

PostgreSQL per second: commits 6218, WAL flushes 1997, WAL flush time 748 ms (max 2327 ms), WAL write time 19 ms
