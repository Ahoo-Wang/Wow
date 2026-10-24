# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 21, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bloat/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.8 ms | 2.0 ms | 3.5 ms | 20.9 ms | 44.7 ms | 33.0 ms |

Load generator: 0.31 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.09 | 1220 MB | 861 MB | 28 | 0.099 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.4% | 162.6% | 1.778GiB / 7.748GiB |
| wow-e2e-bench-postgres | 65.8% | 104.8% | 642.8MiB / 7.748GiB |

Store operations, postgres (seconds -0.048 to 60.952): {epoch=61.0001859664917, xact_commit=312737, xact_rollback=0, tup_inserted=114862, tup_updated=262230, tup_deleted=7, tup_fetched=636103, tup_returned=710920, blks_read=0, blks_hit=3985601, wal_records=1158003, wal_fpi=2, wal_bytes=138271295, wal_buffers_full=0, wal_write=210607, wal_sync=210607, wal_write_time=972.7850000000001, wal_sync_time=29716.50899999999}
Store operations, kafkaEndOffsets (seconds -29.802 to 61.99): {wow.transfer.account.state=169601}

PostgreSQL per second: commits 5127, WAL flushes 3452, WAL flush time 487 ms (max 705 ms), WAL write time 16 ms
