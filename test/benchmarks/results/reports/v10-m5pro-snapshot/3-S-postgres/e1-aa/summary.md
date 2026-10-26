# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-230406-run-postgres-n1-saturation.

Open loop at 11260.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 11260.0/s | 675600 | 0 | 5.8 ms | 15.7 ms | 33.3 ms | 68.1 ms | 110.5 ms | 92.2 ms |

Load generator: 2.73 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.98 | 1352 MB | 860 MB | 88 | 0.365 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.3% | 174.3% | 2.002GiB / 15.6GiB |
| wow-e2e-bench-postgres | 157.3% | 199.7% | 938.2MiB / 15.6GiB |

Store operations, postgres (seconds -0.063 to 60.94): {epoch=61.003705978393555, xact_commit=158763, xact_rollback=0, tup_inserted=677964, tup_updated=1263627, tup_deleted=7, tup_fetched=2404299, tup_returned=2581796, blks_read=244, blks_hit=16674342, wal_records=5783125, wal_fpi=3, wal_bytes=790295548, wal_buffers_full=0, wal_write=97533, wal_sync=97533, wal_write_time=1180.9919999999997, wal_sync_time=44618.333}
Store operations, kafkaEndOffsets (seconds -29.77 to 62.03): {wow.transfer.account.state=1001844}

PostgreSQL per second: commits 2603, WAL flushes 1599, WAL flush time 731 ms (max 769 ms), WAL write time 19 ms
