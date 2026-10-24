# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 30, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/2-F2/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.1 ms | 2.8 ms | 5.6 ms | 29.7 ms | 71.3 ms | 56.8 ms |

Load generator: 0.41 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.34 | 970 MB | 776 MB | 16 | 0.096 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 55.7% | 185.4% | 1.982GiB / 7.748GiB |
| wow-e2e-bench-postgres | 82.0% | 147.8% | 719.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.993 to 60.008): {epoch=61.00093102455139, xact_commit=230127, xact_rollback=0, tup_inserted=116263, tup_updated=263955, tup_deleted=7, tup_fetched=327723, tup_returned=404909, blks_read=0, blks_hit=2917968, wal_records=1169297, wal_fpi=6916, wal_bytes=188388862, wal_buffers_full=0, wal_write=185475, wal_sync=185475, wal_write_time=1486.0710000000004, wal_sync_time=39702.874999999985}
Store operations, kafkaEndOffsets (seconds -29.765 to 62.147): {wow.transfer.account.state=169183}

PostgreSQL per second: commits 3773, WAL flushes 3041, WAL flush time 651 ms (max 789 ms), WAL write time 24 ms
