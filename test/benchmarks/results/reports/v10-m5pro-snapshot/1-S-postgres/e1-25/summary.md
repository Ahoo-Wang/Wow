# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 10, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-220353-run-postgres-n1-saturation.

Open loop at 5630.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5485.2/s | 329109 | 8691 | 5.3 ms | 60.4 ms | 1785.9 ms | 2244.6 ms | 2386.8 ms | 2310.1 ms |

Errors: {http-429-wow.command.busy=8691}

Load generator: 1.61 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.47 | 1318 MB | 759 MB | 76 | 0.190 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.1% | 158.9% | 2.165GiB / 15.6GiB |
| wow-e2e-bench-postgres | 108.5% | 131.9% | 945MiB / 15.6GiB |

Store operations, postgres (seconds -0.054 to 60.948): {epoch=61.00190305709839, xact_commit=163330, xact_rollback=0, tup_inserted=328962, tup_updated=654954, tup_deleted=0, tup_fetched=1346239, tup_returned=7125854, blks_read=71735, blks_hit=16540122, wal_records=2894484, wal_fpi=173, wal_bytes=407328996, wal_buffers_full=0, wal_write=85260, wal_sync=85260, wal_write_time=975.2440000000006, wal_sync_time=43713.61600000004}
Store operations, kafkaEndOffsets (seconds -29.762 to 62.072): {wow.transfer.account.state=500189}

PostgreSQL per second: commits 2677, WAL flushes 1398, WAL flush time 717 ms (max 2306 ms), WAL write time 16 ms
