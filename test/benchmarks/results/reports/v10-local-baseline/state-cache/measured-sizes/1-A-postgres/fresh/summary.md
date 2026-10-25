# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 28, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/1-A-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.5 ms | 2.1 ms | 8.0 ms | 47.9 ms | 95.8 ms | 74.8 ms |

Load generator: 0.30 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.96 | 1065 MB | 769 MB | 17 | 0.084 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.3% | 160.4% | 1.83GiB / 7.748GiB |
| wow-e2e-bench-postgres | 50.6% | 122.2% | 716.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.948 to 60.055): {epoch=61.00353002548218, xact_commit=234485, xact_rollback=0, tup_inserted=116467, tup_updated=265299, tup_deleted=7, tup_fetched=328505, tup_returned=404921, blks_read=0, blks_hit=2908301, wal_records=1172779, wal_fpi=0, wal_bytes=140243854, wal_buffers_full=0, wal_write=212505, wal_sync=212504, wal_write_time=836.9490000000001, wal_sync_time=25156.147999999994}
Store operations, kafkaEndOffsets (seconds -29.653 to 62.018): {wow.transfer.account.state=168553}

PostgreSQL per second: commits 3844, WAL flushes 3484, WAL flush time 412 ms (max 676 ms), WAL write time 14 ms
