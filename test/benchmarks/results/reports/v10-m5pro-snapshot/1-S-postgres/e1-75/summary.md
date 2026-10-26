# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-220353-run-postgres-n1-saturation.

Open loop at 16891.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 16702.7/s | 1002160 | 11300 | 156.7 ms | 214.0 ms | 344.1 ms | 507.9 ms | 882.8 ms | 593.9 ms |

Errors: {http-429-wow.command.busy=11300}

Load generator: 2.66 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.34 | 1267 MB | 853 MB | 170 | 0.760 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.1% | 180.7% | 2.187GiB / 15.6GiB |
| wow-e2e-bench-postgres | 215.7% | 251.0% | 880MiB / 15.6GiB |

Store operations, postgres (seconds -0.06 to 60.938): {epoch=60.99805402755737, xact_commit=50502, xact_rollback=0, tup_inserted=1011138, tup_updated=1188191, tup_deleted=0, tup_fetched=2442541, tup_returned=2467056, blks_read=379274, blks_hit=24919888, wal_records=6781397, wal_fpi=65494, wal_bytes=1266819995, wal_buffers_full=0, wal_write=36158, wal_sync=36158, wal_write_time=898.2700000000004, wal_sync_time=23509.962000000058}
Store operations, kafkaEndOffsets (seconds -30.039 to 62.226): {wow.transfer.account.state=1478582}

PostgreSQL per second: commits 828, WAL flushes 593, WAL flush time 385 ms (max 776 ms), WAL write time 15 ms
