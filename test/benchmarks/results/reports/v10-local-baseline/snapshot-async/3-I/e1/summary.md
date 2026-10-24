# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/3-I/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.0 ms | 5.2 ms | 25.9 ms | 97.8 ms | 155.1 ms | 134.1 ms |

Load generator: 0.43 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.41 | 904 MB | 791 MB | 17 | 0.111 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 58.0% | 197.5% | 2.114GiB / 7.748GiB |
| wow-e2e-bench-postgres | 78.6% | 182.6% | 727.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.008 to 60.992): {epoch=61.00048899650574, xact_commit=223930, xact_rollback=0, tup_inserted=114517, tup_updated=260095, tup_deleted=9, tup_fetched=323823, tup_returned=398729, blks_read=0, blks_hit=2876433, wal_records=1147468, wal_fpi=6921, wal_bytes=186288913, wal_buffers_full=0, wal_write=107046, wal_sync=107046, wal_write_time=1037.8889999999997, wal_sync_time=26995.199999999997}
Store operations, kafkaEndOffsets (seconds -29.748 to 62.104): {wow.transfer.account.state=169030}

PostgreSQL per second: commits 3671, WAL flushes 1755, WAL flush time 443 ms (max 823 ms), WAL write time 17 ms
