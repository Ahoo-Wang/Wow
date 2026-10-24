# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 34, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/2-I/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.8 ms | 6.3 ms | 24.8 ms | 90.6 ms | 144.0 ms | 123.9 ms |

Load generator: 0.42 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.25 | 865 MB | 790 MB | 16 | 0.092 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 55.1% | 169.1% | 1.728GiB / 7.748GiB |
| wow-e2e-bench-postgres | 73.0% | 156.2% | 735.1MiB / 7.748GiB |

Store operations, postgres (seconds -0.982 to 60.019): {epoch=61.00126504898071, xact_commit=227419, xact_rollback=0, tup_inserted=116449, tup_updated=264290, tup_deleted=7, tup_fetched=328147, tup_returned=404545, blks_read=0, blks_hit=2917506, wal_records=1168940, wal_fpi=6803, wal_bytes=187592901, wal_buffers_full=0, wal_write=108128, wal_sync=108128, wal_write_time=928.1980000000003, wal_sync_time=25307.358000000007}
Store operations, kafkaEndOffsets (seconds -29.692 to 62.067): {wow.transfer.account.state=168550}

PostgreSQL per second: commits 3728, WAL flushes 1773, WAL flush time 415 ms (max 854 ms), WAL write time 15 ms
