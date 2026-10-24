# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 31, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s0-up-2.

Open loop at 2542.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2542.0/s | 152520 | 0 | 6.8 ms | 84.0 ms | 129.5 ms | 180.2 ms | 253.4 ms | 199.7 ms |

Load generator: 0.70 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.12 | 1246 MB | 804 MB | 44 | 0.236 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 69.7% | 180.6% | 1.959GiB / 7.748GiB |
| wow-e2e-bench-postgres | 169.2% | 251.4% | 785.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.949 to 60.05): {epoch=60.99984288215637, xact_commit=316492, xact_rollback=0, tup_inserted=154851, tup_updated=328055, tup_deleted=7, tup_fetched=884808, tup_returned=974135, blks_read=0, blks_hit=5344151, wal_records=1425620, wal_fpi=6691, wal_bytes=227485170, wal_buffers_full=0, wal_write=92843, wal_sync=92843, wal_write_time=1644.3380000000002, wal_sync_time=51246.513000000006}
Store operations, kafkaEndOffsets (seconds -29.707 to 62.267): {wow.transfer.account.state=224407}

PostgreSQL per second: commits 5188, WAL flushes 1522, WAL flush time 840 ms (max 939 ms), WAL write time 27 ms
