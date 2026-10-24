# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 24, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/2-D2-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.0 ms | 2.4 ms | 4.7 ms | 22.7 ms | 48.1 ms | 39.9 ms |

Load generator: 0.34 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.29 | 1113 MB | 826 MB | 33 | 0.102 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.8% | 177.3% | 1.985GiB / 7.748GiB |
| wow-e2e-bench-postgres | 75.3% | 109.1% | 695.5MiB / 7.748GiB |

Store operations, postgres (seconds -0.991 to 60.013): {epoch=61.00393009185791, xact_commit=316299, xact_rollback=0, tup_inserted=116627, tup_updated=264676, tup_deleted=7, tup_fetched=722588, tup_returned=799211, blks_read=0, blks_hit=4128296, wal_records=1173810, wal_fpi=7003, wal_bytes=188882535, wal_buffers_full=0, wal_write=205967, wal_sync=205967, wal_write_time=1154.056, wal_sync_time=34459.133}
Store operations, kafkaEndOffsets (seconds -29.741 to 61.998): {wow.transfer.account.state=168353}

PostgreSQL per second: commits 5185, WAL flushes 3376, WAL flush time 565 ms (max 750 ms), WAL write time 19 ms
