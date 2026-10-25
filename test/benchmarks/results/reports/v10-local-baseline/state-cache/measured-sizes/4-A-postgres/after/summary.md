# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 33, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/4-A-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 13.2 ms | 309.2 ms | 462.8 ms | 626.7 ms | 913.6 ms | 679.9 ms |

Load generator: 0.52 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.51 | 863 MB | 835 MB | 19 | 0.498 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 87.2% | 224.9% | 2.155GiB / 7.748GiB |
| wow-e2e-bench-postgres | 201.5% | 362.5% | 863.7MiB / 7.748GiB |

Store operations, postgres (seconds -0.922 to 60.077): {epoch=60.99905014038086, xact_commit=170555, xact_rollback=0, tup_inserted=116446, tup_updated=234733, tup_deleted=0, tup_fetched=1132598, tup_returned=1179126, blks_read=496737, blks_hit=3480021, wal_records=1063336, wal_fpi=19075, wal_bytes=249616782, wal_buffers_full=0, wal_write=75387, wal_sync=75387, wal_write_time=1633.0480000000007, wal_sync_time=48068.658999999985}
Store operations, kafkaEndOffsets (seconds -29.966 to 62.498): {wow.transfer.account.state=169608}

PostgreSQL per second: commits 2796, WAL flushes 1236, WAL flush time 788 ms (max 895 ms), WAL write time 27 ms
