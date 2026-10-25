# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon/6-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.7 ms | 2.3 ms | 5.1 ms | 21.8 ms | 49.6 ms | 33.5 ms |

Load generator: 0.41 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.32 | 993 MB | 737 MB | 14 | 0.060 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.9% | 158.8% | 1.703GiB / 7.748GiB |
| wow-e2e-bench-postgres | 68.9% | 136.9% | 627.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.992 to 40.012): {epoch=41.00468587875366, xact_commit=195133, xact_rollback=0, tup_inserted=102742, tup_updated=226851, tup_deleted=7, tup_fetched=291324, tup_returned=352338, blks_read=0, blks_hit=2562231, wal_records=1048106, wal_fpi=2, wal_bytes=125300205, wal_buffers_full=0, wal_write=173609, wal_sync=173609, wal_write_time=783.5219999999999, wal_sync_time=25126.512}
Store operations, kafkaEndOffsets (seconds -19.778 to 41.833): {wow.transfer.account.state=147116}

PostgreSQL per second: commits 4759, WAL flushes 4234, WAL flush time 613 ms (max 752 ms), WAL write time 19 ms
