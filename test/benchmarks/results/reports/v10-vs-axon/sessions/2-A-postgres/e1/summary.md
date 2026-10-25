# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon-2/2-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 2.1 ms | 5.4 ms | 20.4 ms | 32.8 ms | 61.8 ms | 30.5 ms |

Load generator: 0.49 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.36 | 984 MB | 950 MB | 22 | 0.500 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.4% | 185.3% | 394.8MiB / 7.748GiB |
| wow-e2e-bench-postgres | 101.4% | 135.0% | 257.6MiB / 7.748GiB |

Store operations, postgres (seconds 0.0 to 40.001): {epoch=40.00059485435486, xact_commit=157460, xact_rollback=0, tup_inserted=99991, tup_updated=909, tup_deleted=6, tup_fetched=510555, tup_returned=618016, blks_read=0, blks_hit=2997919, wal_records=512333, wal_fpi=2, wal_bytes=64707770, wal_buffers_full=0, wal_write=91861, wal_sync=91861, wal_write_time=783.8719999999998, wal_sync_time=25437.846999999994}

PostgreSQL per second: commits 3936, WAL flushes 2296, WAL flush time 636 ms (max 777 ms), WAL write time 20 ms
