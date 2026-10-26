# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-230127-run-postgres-n1-saturation.

Open loop at 11260.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 11260.0/s | 675600 | 0 | 6.0 ms | 15.2 ms | 31.7 ms | 85.5 ms | 152.8 ms | 127.5 ms |

Load generator: 2.72 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.93 | 1363 MB | 831 MB | 89 | 0.369 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.8% | 176.6% | 2.076GiB / 15.6GiB |
| wow-e2e-bench-postgres | 157.9% | 198.0% | 977.9MiB / 15.6GiB |

Store operations, postgres (seconds -0.063 to 60.939): {epoch=61.002086877822876, xact_commit=158511, xact_rollback=0, tup_inserted=678665, tup_updated=1263989, tup_deleted=7, tup_fetched=2404891, tup_returned=5113995, blks_read=261, blks_hit=19500295, wal_records=5780984, wal_fpi=2, wal_bytes=790057674, wal_buffers_full=0, wal_write=98214, wal_sync=98214, wal_write_time=1156.871, wal_sync_time=44282.414}
Store operations, kafkaEndOffsets (seconds -29.766 to 62.03): {wow.transfer.account.state=1001924}

PostgreSQL per second: commits 2598, WAL flushes 1610, WAL flush time 726 ms (max 769 ms), WAL write time 19 ms
