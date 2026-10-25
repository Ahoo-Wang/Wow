# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e2/1-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.8 ms | 3.5 ms | 6.1 ms | 27.6 ms | 57.4 ms | 50.2 ms |

Load generator: 0.20 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.27 | 966 MB | 731 MB | 10 | 0.052 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.1% | 178.2% | 1.914GiB / 7.748GiB |
| wow-e2e-bench-postgres | 75.8% | 104.0% | 878.1MiB / 7.748GiB |

Store operations, postgres (seconds -0.039 to 40.96): {epoch=40.999216079711914, xact_commit=163860, xact_rollback=0, tup_inserted=84029, tup_updated=189864, tup_deleted=0, tup_fetched=235657, tup_returned=277017, blks_read=0, blks_hit=2194690, wal_records=838990, wal_fpi=0, wal_bytes=123678769, wal_buffers_full=0, wal_write=135510, wal_sync=135510, wal_write_time=782.5230000000001, wal_sync_time=25266.56899999999}
Store operations, kafkaEndOffsets (seconds -20.018 to 41.917): {wow.transfer.account.state=123658}

PostgreSQL per second: commits 3997, WAL flushes 3305, WAL flush time 616 ms (max 784 ms), WAL write time 19 ms
