# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e/1-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.5 ms | 3.0 ms | 5.9 ms | 33.5 ms | 62.3 ms | 55.8 ms |

Load generator: 0.17 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.11 | 907 MB | 652 MB | 10 | 0.051 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 59.6% | 177.2% | 1.953GiB / 7.748GiB |
| wow-e2e-bench-postgres | 72.1% | 98.2% | 863.8MiB / 7.748GiB |

Store operations, postgres (seconds -0.055 to 40.951): {epoch=41.00506806373596, xact_commit=165404, xact_rollback=0, tup_inserted=84238, tup_updated=190209, tup_deleted=0, tup_fetched=235438, tup_returned=277584, blks_read=0, blks_hit=2198898, wal_records=842116, wal_fpi=0, wal_bytes=123969340, wal_buffers_full=0, wal_write=142838, wal_sync=142838, wal_write_time=745.7640000000001, wal_sync_time=23687.293999999994}
Store operations, kafkaEndOffsets (seconds -20.028 to 41.858): {wow.transfer.account.state=124223}

PostgreSQL per second: commits 4034, WAL flushes 3484, WAL flush time 578 ms (max 730 ms), WAL write time 18 ms
