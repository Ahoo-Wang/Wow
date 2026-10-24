# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 6, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/1-b32-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 2.1 ms | 102.4 ms | 280.6 ms | 452.6 ms | 688.3 ms | 569.3 ms |

Load generator: 0.52 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.56 | 914 MB | 883 MB | 18 | 0.379 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 72.5% | 211.3% | 2.047GiB / 7.748GiB |
| wow-e2e-bench-postgres | 124.9% | 285.0% | 1.192GiB / 7.748GiB |

Store operations, postgres (seconds -0.971 to 60.024): {epoch=60.99489212036133, xact_commit=201815, xact_rollback=0, tup_inserted=116037, tup_updated=255335, tup_deleted=0, tup_fetched=407514, tup_returned=468139, blks_read=21142, blks_hit=2986965, wal_records=1114595, wal_fpi=45, wal_bytes=139542232, wal_buffers_full=0, wal_write=127385, wal_sync=127385, wal_write_time=1577.4680000000008, wal_sync_time=44731.71599999996}
Store operations, kafkaEndOffsets (seconds -29.709 to 62.416): {wow.transfer.account.state=197325}

PostgreSQL per second: commits 3309, WAL flushes 2088, WAL flush time 733 ms (max 886 ms), WAL write time 26 ms
