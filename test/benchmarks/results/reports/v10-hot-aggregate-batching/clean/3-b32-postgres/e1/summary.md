# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 23, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch-clean/3-b32-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.6 ms | 1.5 ms | 2.4 ms | 18.7 ms | 48.7 ms | 39.4 ms |

Load generator: 0.30 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.00 | 1130 MB | 759 MB | 17 | 0.080 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.9% | 174.1% | 1.805GiB / 7.748GiB |
| wow-e2e-bench-postgres | 49.7% | 114.2% | 659.5MiB / 7.748GiB |

Store operations, postgres (seconds -0.965 to 60.036): {epoch=61.00114893913269, xact_commit=236701, xact_rollback=0, tup_inserted=116330, tup_updated=265110, tup_deleted=7, tup_fetched=328364, tup_returned=404198, blks_read=0, blks_hit=2904261, wal_records=1172551, wal_fpi=2, wal_bytes=140119777, wal_buffers_full=0, wal_write=218046, wal_sync=218046, wal_write_time=959.3219999999999, wal_sync_time=27251.185999999994}
Store operations, kafkaEndOffsets (seconds -29.823 to 62.013): {wow.transfer.account.state=169638}

PostgreSQL per second: commits 3880, WAL flushes 3575, WAL flush time 447 ms (max 712 ms), WAL write time 16 ms
