# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 30, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/1-G-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.2 ms | 5.2 ms | 34.0 ms | 120.3 ms | 179.8 ms | 154.6 ms |

Load generator: 0.43 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.46 | 922 MB | 780 MB | 16 | 0.105 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 59.0% | 179.9% | 1.969GiB / 7.748GiB |
| wow-e2e-bench-postgres | 84.5% | 152.4% | 745.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.962 to 60.039): {epoch=61.000916957855225, xact_commit=223145, xact_rollback=0, tup_inserted=115856, tup_updated=263238, tup_deleted=7, tup_fetched=326967, tup_returned=403193, blks_read=0, blks_hit=2909202, wal_records=1159957, wal_fpi=6918, wal_bytes=187458302, wal_buffers_full=0, wal_write=171584, wal_sync=171584, wal_write_time=1407.366, wal_sync_time=39128.60299999999}
Store operations, kafkaEndOffsets (seconds -29.802 to 62.129): {wow.transfer.account.state=168355}

PostgreSQL per second: commits 3658, WAL flushes 2813, WAL flush time 641 ms (max 872 ms), WAL write time 23 ms
