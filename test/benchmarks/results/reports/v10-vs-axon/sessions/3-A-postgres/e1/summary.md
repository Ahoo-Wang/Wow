# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 27, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon-2/3-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 2.1 ms | 4.4 ms | 10.9 ms | 25.3 ms | 51.1 ms | 33.3 ms |

Load generator: 0.54 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.29 | 1086 MB | 646 MB | 21 | 0.162 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.3% | 172.5% | 377.1MiB / 7.748GiB |
| wow-e2e-bench-postgres | 100.3% | 135.3% | 251.8MiB / 7.748GiB |

Store operations, postgres (seconds -0.004 to 39.996): {epoch=39.99963092803955, xact_commit=158975, xact_rollback=0, tup_inserted=100159, tup_updated=1121, tup_deleted=6, tup_fetched=515064, tup_returned=613917, blks_read=0, blks_hit=3020675, wal_records=513567, wal_fpi=2, wal_bytes=64831680, wal_buffers_full=0, wal_write=92399, wal_sync=92399, wal_write_time=771.012, wal_sync_time=25488.381}

PostgreSQL per second: commits 3974, WAL flushes 2310, WAL flush time 637 ms (max 757 ms), WAL write time 19 ms
