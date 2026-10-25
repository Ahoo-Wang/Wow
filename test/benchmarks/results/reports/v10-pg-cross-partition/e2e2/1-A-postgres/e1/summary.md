# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e2/1-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.2 ms | 3.4 ms | 15.4 ms | 48.6 ms | 90.0 ms | 62.5 ms |

Load generator: 0.51 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.69 | 908 MB | 727 MB | 14 | 0.088 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.9% | 179.5% | 1.738GiB / 7.748GiB |
| wow-e2e-bench-postgres | 96.3% | 127.7% | 767.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.95 to 40.05): {epoch=40.99979019165039, xact_commit=189199, xact_rollback=0, tup_inserted=101826, tup_updated=224267, tup_deleted=7, tup_fetched=264699, tup_returned=332423, blks_read=0, blks_hit=2496451, wal_records=1021993, wal_fpi=3, wal_bytes=123403824, wal_buffers_full=0, wal_write=131906, wal_sync=131906, wal_write_time=1080.1029999999998, wal_sync_time=31733.087000000007}
Store operations, kafkaEndOffsets (seconds -19.761 to 41.935): {wow.transfer.account.state=145880}

PostgreSQL per second: commits 4615, WAL flushes 3217, WAL flush time 774 ms (max 906 ms), WAL write time 26 ms
