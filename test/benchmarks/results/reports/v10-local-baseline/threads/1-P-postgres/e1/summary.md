# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 25, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/1-P-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.1 ms | 2.5 ms | 5.1 ms | 23.4 ms | 58.0 ms | 42.8 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.47 | 1189 MB | 818 MB | 29 | 0.123 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.9% | 179.4% | 2.099GiB / 7.748GiB |
| wow-e2e-bench-postgres | 79.9% | 123.5% | 793.6MiB / 7.748GiB |

Store operations, postgres (seconds -0.999 to 60.001): {epoch=61.00003790855408, xact_commit=314843, xact_rollback=0, tup_inserted=116303, tup_updated=265203, tup_deleted=9, tup_fetched=721803, tup_returned=798089, blks_read=0, blks_hit=4133545, wal_records=1175277, wal_fpi=6854, wal_bytes=189010576, wal_buffers_full=0, wal_write=200398, wal_sync=200398, wal_write_time=1179.0810000000001, wal_sync_time=36206.37700000001}
Store operations, kafkaEndOffsets (seconds -29.754 to 62.019): {wow.transfer.account.state=168210}

PostgreSQL per second: commits 5161, WAL flushes 3285, WAL flush time 594 ms (max 758 ms), WAL write time 19 ms
