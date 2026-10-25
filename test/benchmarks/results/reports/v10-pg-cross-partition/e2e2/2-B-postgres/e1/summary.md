# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 12, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e2/2-B-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.1 ms | 3.0 ms | 7.5 ms | 34.0 ms | 55.2 ms | 43.5 ms |

Load generator: 0.48 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.57 | 1065 MB | 746 MB | 18 | 0.108 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.2% | 179.2% | 1.807GiB / 7.748GiB |
| wow-e2e-bench-postgres | 88.7% | 132.0% | 635.1MiB / 7.748GiB |

Store operations, postgres (seconds -0.951 to 40.048): {epoch=40.999621868133545, xact_commit=182518, xact_rollback=0, tup_inserted=102273, tup_updated=226230, tup_deleted=7, tup_fetched=296439, tup_returned=360958, blks_read=0, blks_hit=2575627, wal_records=1042732, wal_fpi=3, wal_bytes=124874291, wal_buffers_full=0, wal_write=141084, wal_sync=141084, wal_write_time=1011.1369999999997, wal_sync_time=28996.689999999995}
Store operations, kafkaEndOffsets (seconds -19.757 to 41.913): {wow.transfer.account.state=146335}

PostgreSQL per second: commits 4452, WAL flushes 3441, WAL flush time 707 ms (max 755 ms), WAL write time 25 ms
