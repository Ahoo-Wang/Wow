# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache/1-large-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.5 ms | 1.4 ms | 2.2 ms | 18.0 ms | 49.0 ms | 41.5 ms |

Load generator: 0.29 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.91 | 1305 MB | 966 MB | 30 | 0.076 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 44.1% | 179.0% | 1.977GiB / 7.748GiB |
| wow-e2e-bench-postgres | 45.8% | 123.3% | 820.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.999 to 60.002): {epoch=61.00137710571289, xact_commit=231693, xact_rollback=0, tup_inserted=116329, tup_updated=265174, tup_deleted=7, tup_fetched=313700, tup_returned=389429, blks_read=0, blks_hit=2849572, wal_records=1179451, wal_fpi=7218, wal_bytes=190075570, wal_buffers_full=0, wal_write=220693, wal_sync=220693, wal_write_time=815.3650000000002, wal_sync_time=25081.314}
Store operations, kafkaEndOffsets (seconds -29.821 to 61.943): {wow.transfer.account.state=169668}

PostgreSQL per second: commits 3798, WAL flushes 3618, WAL flush time 411 ms (max 669 ms), WAL write time 13 ms
