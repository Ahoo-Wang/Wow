# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/2-b1-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.6 ms | 1.7 ms | 2.6 ms | 13.3 ms | 38.4 ms | 29.6 ms |

Load generator: 0.31 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.12 | 902 MB | 819 MB | 17 | 0.089 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.1% | 171.3% | 2.119GiB / 7.748GiB |
| wow-e2e-bench-postgres | 55.3% | 101.1% | 971.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.994 to 60.008): {epoch=61.00139093399048, xact_commit=239554, xact_rollback=0, tup_inserted=116076, tup_updated=263768, tup_deleted=9, tup_fetched=378122, tup_returned=429391, blks_read=9008, blks_hit=2997147, wal_records=1179902, wal_fpi=173, wal_bytes=143417949, wal_buffers_full=0, wal_write=213011, wal_sync=213011, wal_write_time=1027.567000000001, wal_sync_time=29669.24999999997}
Store operations, kafkaEndOffsets (seconds -29.762 to 62.024): {wow.transfer.account.state=188754}

PostgreSQL per second: commits 3927, WAL flushes 3492, WAL flush time 486 ms (max 702 ms), WAL write time 17 ms
