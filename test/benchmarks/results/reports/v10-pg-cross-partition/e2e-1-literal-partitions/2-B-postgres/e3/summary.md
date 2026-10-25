# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e/2-B-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 5.0 ms | 12.9 ms | 20.9 ms | 66.0 ms | 101.9 ms | 71.2 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.17 | 930 MB | 790 MB | 11 | 0.066 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 58.9% | 178.9% | 1.867GiB / 7.748GiB |
| wow-e2e-bench-postgres | 125.5% | 184.5% | 507MiB / 7.748GiB |

Store operations, postgres (seconds -0.041 to 40.961): {epoch=41.00228500366211, xact_commit=133264, xact_rollback=0, tup_inserted=84249, tup_updated=189431, tup_deleted=0, tup_fetched=301217, tup_returned=345203, blks_read=0, blks_hit=2370862, wal_records=849808, wal_fpi=0, wal_bytes=125225140, wal_buffers_full=0, wal_write=115817, wal_sync=115817, wal_write_time=696.9589999999998, wal_sync_time=21499.785000000003}
Store operations, kafkaEndOffsets (seconds -20.026 to 41.837): {wow.transfer.account.state=124548}

PostgreSQL per second: commits 3250, WAL flushes 2825, WAL flush time 524 ms (max 667 ms), WAL write time 17 ms
