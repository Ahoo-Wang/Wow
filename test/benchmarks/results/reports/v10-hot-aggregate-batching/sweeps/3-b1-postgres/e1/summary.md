# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 23, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/3-b1-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.7 ms | 1.7 ms | 2.8 ms | 21.0 ms | 58.0 ms | 34.8 ms |

Load generator: 0.31 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.09 | 870 MB | 753 MB | 17 | 0.089 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.1% | 185.9% | 2.096GiB / 7.748GiB |
| wow-e2e-bench-postgres | 56.6% | 137.7% | 991.7MiB / 7.748GiB |

Store operations, postgres (seconds -0.991 to 60.01): {epoch=61.00063180923462, xact_commit=239057, xact_rollback=0, tup_inserted=115950, tup_updated=263843, tup_deleted=18, tup_fetched=388040, tup_returned=430815, blks_read=17575, blks_hit=3032357, wal_records=1177695, wal_fpi=185, wal_bytes=143008991, wal_buffers_full=0, wal_write=210669, wal_sync=210669, wal_write_time=1049.4869999999992, wal_sync_time=31055.641000000032}
Store operations, kafkaEndOffsets (seconds -29.747 to 62.019): {wow.transfer.account.state=189395}

PostgreSQL per second: commits 3919, WAL flushes 3454, WAL flush time 509 ms (max 715 ms), WAL write time 17 ms
