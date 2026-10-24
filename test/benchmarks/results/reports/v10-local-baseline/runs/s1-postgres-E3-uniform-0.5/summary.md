# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 41, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-postgres-up.

Open loop at 830.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 830.0/s | 49799 | 0 | 9.3 ms | 93.7 ms | 210.9 ms | 479.2 ms | 574.3 ms | 540.7 ms |

Load generator: 0.27 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.76 | 1097 MB | 829 MB | 31 | 0.301 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 69.1% | 176.5% | 2.061GiB / 7.748GiB |
| wow-e2e-bench-postgres | 193.5% | 281.5% | 944.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.045 to 60.955): {epoch=61.00035786628723, xact_commit=290302, xact_rollback=0, tup_inserted=150077, tup_updated=325259, tup_deleted=12, tup_fetched=2399243, tup_returned=2423667, blks_read=1276446, blks_hit=5426744, wal_records=1436798, wal_fpi=24562, wal_bytes=357741734, wal_buffers_full=0, wal_write=96974, wal_sync=96974, wal_write_time=1732.0169999999998, wal_sync_time=50572.82899999991}
Store operations, kafkaEndOffsets (seconds -30.021 to 62.176): {wow.transfer.account.state=233978}

PostgreSQL per second: commits 4759, WAL flushes 1590, WAL flush time 829 ms (max 910 ms), WAL write time 28 ms
