# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 27, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon-2/3-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.8 ms | 6.8 ms | 9.7 ms | 19.2 ms | 25.0 ms | 19.2 ms |

Load generator: 0.15 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.93 | 945 MB | 666 MB | 15 | 0.124 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.6% | 180.4% | 506.4MiB / 7.748GiB |
| wow-e2e-bench-postgres | 79.8% | 107.3% | 347.5MiB / 7.748GiB |

Store operations, postgres (seconds -0.055 to 40.95): {epoch=41.00439214706421, xact_commit=156692, xact_rollback=0, tup_inserted=112054, tup_updated=57346, tup_deleted=0, tup_fetched=644055, tup_returned=756271, blks_read=0, blks_hit=3300970, wal_records=598157, wal_fpi=0, wal_bytes=88502984, wal_buffers_full=0, wal_write=79936, wal_sync=79936, wal_write_time=522.7019999999998, wal_sync_time=16015.562000000005}

PostgreSQL per second: commits 3822, WAL flushes 1950, WAL flush time 391 ms (max 553 ms), WAL write time 13 ms
