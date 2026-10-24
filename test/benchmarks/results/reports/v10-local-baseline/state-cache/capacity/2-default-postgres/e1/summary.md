# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 38, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache/2-default-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 2.5 ms | 46.8 ms | 132.1 ms | 201.7 ms | 279.2 ms | 214.0 ms |

Load generator: 0.53 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.69 | 1054 MB | 807 MB | 32 | 0.167 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 68.8% | 183.7% | 1.949GiB / 7.748GiB |
| wow-e2e-bench-postgres | 137.7% | 241.5% | 727.8MiB / 7.748GiB |

Store operations, postgres (seconds -0.998 to 60.003): {epoch=61.00142216682434, xact_commit=284255, xact_rollback=0, tup_inserted=116233, tup_updated=262000, tup_deleted=7, tup_fetched=694226, tup_returned=770306, blks_read=0, blks_hit=4096514, wal_records=1141059, wal_fpi=6923, wal_bytes=186650684, wal_buffers_full=0, wal_write=121521, wal_sync=121521, wal_write_time=1906.6570000000002, wal_sync_time=47431.720000000016}
Store operations, kafkaEndOffsets (seconds -29.738 to 62.229): {wow.transfer.account.state=169100}

PostgreSQL per second: commits 4660, WAL flushes 1992, WAL flush time 778 ms (max 869 ms), WAL write time 31 ms
