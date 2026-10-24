# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 41, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-postgres-up.

Open loop at 415.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 415.0/s | 24899 | 0 | 2.5 ms | 364.5 ms | 974.8 ms | 1237.0 ms | 1433.7 ms | 1376.3 ms |

Load generator: 0.13 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.81 | 1034 MB | 799 MB | 16 | 0.352 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.9% | 194.4% | 2.137GiB / 7.748GiB |
| wow-e2e-bench-postgres | 83.7% | 332.1% | 840.5MiB / 7.748GiB |

Store operations, postgres (seconds -0.05 to 60.943): {epoch=60.993250131607056, xact_commit=177720, xact_rollback=0, tup_inserted=75500, tup_updated=179829, tup_deleted=0, tup_fetched=1156396, tup_returned=1173116, blks_read=548329, blks_hit=2859608, wal_records=785815, wal_fpi=274, wal_bytes=114356187, wal_buffers_full=0, wal_write=124989, wal_sync=124989, wal_write_time=733.7740000000013, wal_sync_time=26370.841999999946}
Store operations, kafkaEndOffsets (seconds -29.987 to 62.137): {wow.transfer.account.state=126058}

PostgreSQL per second: commits 2913, WAL flushes 2049, WAL flush time 432 ms (max 856 ms), WAL write time 12 ms
