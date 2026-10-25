# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e/2-B-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 3.8 ms | 7.8 ms | 16.8 ms | 138.2 ms | 194.0 ms | 162.8 ms |

Load generator: 0.57 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.51 | 1005 MB | 765 MB | 16 | 0.091 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 59.2% | 178.8% | 1.727GiB / 7.748GiB |
| wow-e2e-bench-postgres | 175.4% | 223.7% | 397.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.944 to 40.062): {epoch=41.00534987449646, xact_commit=115972, xact_rollback=0, tup_inserted=100848, tup_updated=221351, tup_deleted=7, tup_fetched=414840, tup_returned=480940, blks_read=0, blks_hit=2858415, wal_records=1033235, wal_fpi=3, wal_bytes=124713019, wal_buffers_full=0, wal_write=89359, wal_sync=89359, wal_write_time=822.358, wal_sync_time=22358.695}
Store operations, kafkaEndOffsets (seconds -19.718 to 41.907): {wow.transfer.account.state=145885}

PostgreSQL per second: commits 2828, WAL flushes 2179, WAL flush time 545 ms (max 578 ms), WAL write time 20 ms
