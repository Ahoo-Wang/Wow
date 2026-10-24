# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 20, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache/1-default-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.6 ms | 1.7 ms | 2.4 ms | 17.8 ms | 51.2 ms | 41.7 ms |

Load generator: 0.26 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.92 | 1315 MB | 838 MB | 34 | 0.091 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.5% | 152.1% | 1.955GiB / 7.748GiB |
| wow-e2e-bench-postgres | 50.0% | 89.1% | 767.6MiB / 7.748GiB |

Store operations, postgres (seconds -0.999 to 60.001): {epoch=61.00056290626526, xact_commit=318100, xact_rollback=0, tup_inserted=116394, tup_updated=265850, tup_deleted=14, tup_fetched=738624, tup_returned=816868, blks_read=0, blks_hit=4182723, wal_records=1184144, wal_fpi=7036, wal_bytes=189790200, wal_buffers_full=0, wal_write=221623, wal_sync=221623, wal_write_time=659.249, wal_sync_time=22730.774999999994}
Store operations, kafkaEndOffsets (seconds -29.795 to 61.921): {wow.transfer.account.state=169527}

PostgreSQL per second: commits 5215, WAL flushes 3633, WAL flush time 373 ms (max 586 ms), WAL write time 11 ms
