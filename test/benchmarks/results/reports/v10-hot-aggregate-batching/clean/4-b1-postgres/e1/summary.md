# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 23, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch-clean/4-b1-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.6 ms | 1.5 ms | 2.4 ms | 18.8 ms | 63.0 ms | 44.5 ms |

Load generator: 0.33 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.01 | 993 MB | 782 MB | 16 | 0.074 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.8% | 170.6% | 1.886GiB / 7.748GiB |
| wow-e2e-bench-postgres | 50.6% | 126.1% | 658.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.966 to 60.038): {epoch=61.004343032836914, xact_commit=235962, xact_rollback=0, tup_inserted=116088, tup_updated=264910, tup_deleted=7, tup_fetched=328029, tup_returned=404077, blks_read=0, blks_hit=2900723, wal_records=1171075, wal_fpi=2, wal_bytes=139849885, wal_buffers_full=0, wal_write=217267, wal_sync=217267, wal_write_time=970.9469999999999, wal_sync_time=27435.749999999993}
Store operations, kafkaEndOffsets (seconds -29.826 to 62.047): {wow.transfer.account.state=169635}

PostgreSQL per second: commits 3868, WAL flushes 3561, WAL flush time 450 ms (max 706 ms), WAL write time 16 ms
