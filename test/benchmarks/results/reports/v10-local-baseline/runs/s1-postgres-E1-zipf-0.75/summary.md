# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 41, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-postgres-up.

Open loop at 3266.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3266.0/s | 195960 | 0 | 1.8 ms | 19.5 ms | 150.5 ms | 292.9 ms | 321.7 ms | 309.2 ms |

Load generator: 0.74 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.16 | 1014 MB | 823 MB | 31 | 0.148 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.6% | 177.5% | 2.155GiB / 7.748GiB |
| wow-e2e-bench-postgres | 132.2% | 158.2% | 887.7MiB / 7.748GiB |

Store operations, postgres (seconds -0.062 to 60.939): {epoch=61.00050592422485, xact_commit=334298, xact_rollback=0, tup_inserted=196299, tup_updated=418547, tup_deleted=0, tup_fetched=827120, tup_returned=896688, blks_read=100076, blks_hit=4909564, wal_records=1868985, wal_fpi=12276, wal_bytes=309131015, wal_buffers_full=0, wal_write=142270, wal_sync=142270, wal_write_time=1554.5649999999987, wal_sync_time=51660.01400000008}
Store operations, kafkaEndOffsets (seconds -30.036 to 62.173): {wow.transfer.account.state=265085}

PostgreSQL per second: commits 5480, WAL flushes 2332, WAL flush time 847 ms (max 915 ms), WAL write time 25 ms
