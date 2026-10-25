# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 33, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/4-A-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.6 ms | 1.8 ms | 3.1 ms | 20.1 ms | 49.7 ms | 34.8 ms |

Load generator: 0.33 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.02 | 964 MB | 766 MB | 16 | 0.080 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.2% | 174.4% | 1.847GiB / 7.748GiB |
| wow-e2e-bench-postgres | 57.8% | 140.5% | 692.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.978 to 60.027): {epoch=61.00510811805725, xact_commit=235233, xact_rollback=0, tup_inserted=116340, tup_updated=264704, tup_deleted=7, tup_fetched=326376, tup_returned=404052, blks_read=0, blks_hit=2901559, wal_records=1174227, wal_fpi=0, wal_bytes=140206942, wal_buffers_full=0, wal_write=212936, wal_sync=212936, wal_write_time=1053.788, wal_sync_time=28291.790999999997}
Store operations, kafkaEndOffsets (seconds -29.723 to 61.964): {wow.transfer.account.state=168720}

PostgreSQL per second: commits 3856, WAL flushes 3490, WAL flush time 464 ms (max 699 ms), WAL write time 17 ms
