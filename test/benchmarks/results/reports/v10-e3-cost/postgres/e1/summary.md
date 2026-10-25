# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 13, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/e3/runs/postgres/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 0.3 ms | 0.7 ms | 1.0 ms | 4.4 ms | 13.2 ms | 5.0 ms |

Load generator: 0.22 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.69 | 1130 MB | 650 MB | 25 | 0.083 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.2% | 130.6% | 1.727GiB / 7.748GiB |
| wow-e2e-bench-postgres | 27.2% | 74.7% | 1.365GiB / 7.748GiB |

Store operations, postgres (seconds -0.045 to 60.957): {epoch=61.00133395195007, xact_commit=208136, xact_rollback=0, tup_inserted=103774, tup_updated=239644, tup_deleted=7, tup_fetched=279745, tup_returned=351045, blks_read=0, blks_hit=2544043, wal_records=1054412, wal_fpi=2, wal_bytes=125448708, wal_buffers_full=0, wal_write=207466, wal_sync=207466, wal_write_time=307.3660000000001, wal_sync_time=55.90700000000001}
Store operations, kafkaEndOffsets (seconds -29.835 to 61.971): {wow.transfer.account.state=153023}

PostgreSQL per second: commits 3412, WAL flushes 3401, WAL flush time 1 ms (max 1 ms), WAL write time 5 ms
