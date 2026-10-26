# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-220353-run-postgres-n1-saturation.

Open loop at 11260.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 11169.0/s | 670142 | 5458 | 107.5 ms | 177.2 ms | 321.5 ms | 860.2 ms | 1133.3 ms | 950.3 ms |

Errors: {http-429-wow.command.busy=5458}

Load generator: 2.45 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.75 | 1309 MB | 814 MB | 104 | 0.412 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.9% | 171.6% | 2.159GiB / 15.6GiB |
| wow-e2e-bench-postgres | 166.1% | 199.3% | 945.3MiB / 15.6GiB |

Store operations, postgres (seconds -0.056 to 60.943): {epoch=60.99904012680054, xact_commit=90193, xact_rollback=0, tup_inserted=676610, tup_updated=968924, tup_deleted=0, tup_fetched=2015879, tup_returned=2022917, blks_read=346203, blks_hit=18281280, wal_records=5081793, wal_fpi=91, wal_bytes=782874273, wal_buffers_full=0, wal_write=56286, wal_sync=56286, wal_write_time=941.5680000000011, wal_sync_time=33266.56199999992}
Store operations, kafkaEndOffsets (seconds -30.028 to 62.188): {wow.transfer.account.state=998051}

PostgreSQL per second: commits 1479, WAL flushes 923, WAL flush time 545 ms (max 1044 ms), WAL write time 15 ms
