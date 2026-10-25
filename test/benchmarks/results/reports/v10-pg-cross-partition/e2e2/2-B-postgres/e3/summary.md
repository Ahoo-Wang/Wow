# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 12, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e2/2-B-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.5 ms | 4.4 ms | 7.5 ms | 36.9 ms | 61.6 ms | 48.1 ms |

Load generator: 0.18 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.16 | 989 MB | 759 MB | 10 | 0.046 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.1% | 178.6% | 1.992GiB / 7.748GiB |
| wow-e2e-bench-postgres | 72.4% | 105.5% | 746MiB / 7.748GiB |

Store operations, postgres (seconds -0.05 to 40.954): {epoch=41.00408697128296, xact_commit=162124, xact_rollback=0, tup_inserted=84484, tup_updated=191014, tup_deleted=0, tup_fetched=239761, tup_returned=285821, blks_read=0, blks_hit=2219763, wal_records=847795, wal_fpi=0, wal_bytes=124961207, wal_buffers_full=0, wal_write=141581, wal_sync=141581, wal_write_time=763.404, wal_sync_time=23968.584999999992}
Store operations, kafkaEndOffsets (seconds -20.029 to 41.894): {wow.transfer.account.state=124507}

PostgreSQL per second: commits 3954, WAL flushes 3453, WAL flush time 585 ms (max 737 ms), WAL write time 19 ms
