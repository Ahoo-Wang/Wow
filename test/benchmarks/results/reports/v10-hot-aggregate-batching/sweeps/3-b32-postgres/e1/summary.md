# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 27, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/3-b32-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.8 ms | 2.1 ms | 8.1 ms | 53.5 ms | 114.4 ms | 72.2 ms |

Load generator: 0.36 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.17 | 1082 MB | 848 MB | 20 | 0.136 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.4% | 178.6% | 1.999GiB / 7.748GiB |
| wow-e2e-bench-postgres | 66.7% | 104.6% | 1.188GiB / 7.748GiB |

Store operations, postgres (seconds -0.971 to 60.031): {epoch=61.00139093399048, xact_commit=242702, xact_rollback=0, tup_inserted=116334, tup_updated=264238, tup_deleted=0, tup_fetched=402100, tup_returned=468869, blks_read=16662, blks_hit=2985360, wal_records=1166045, wal_fpi=1589, wal_bytes=149540554, wal_buffers_full=0, wal_write=202397, wal_sync=202397, wal_write_time=1162.911, wal_sync_time=33526.85800000001}
Store operations, kafkaEndOffsets (seconds -29.725 to 62.093): {wow.transfer.account.state=200194}

PostgreSQL per second: commits 3979, WAL flushes 3318, WAL flush time 550 ms (max 741 ms), WAL write time 19 ms
