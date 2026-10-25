# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 27, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/2-B-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.5 ms | 1.8 ms | 3.6 ms | 18.2 ms | 43.3 ms | 32.6 ms |

Load generator: 0.33 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.06 | 939 MB | 715 MB | 28 | 0.084 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.2% | 181.4% | 1.996GiB / 7.748GiB |
| wow-e2e-bench-postgres | 55.1% | 104.2% | 801.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.96 to 60.046): {epoch=61.00507998466492, xact_commit=226389, xact_rollback=0, tup_inserted=114443, tup_updated=260905, tup_deleted=7, tup_fetched=307744, tup_returned=382960, blks_read=0, blks_hit=2795636, wal_records=1159003, wal_fpi=0, wal_bytes=138077775, wal_buffers_full=0, wal_write=209566, wal_sync=209566, wal_write_time=1011.3209999999999, wal_sync_time=28015.497000000003}
Store operations, kafkaEndOffsets (seconds -29.704 to 61.971): {wow.transfer.account.state=168688}

PostgreSQL per second: commits 3711, WAL flushes 3435, WAL flush time 459 ms (max 733 ms), WAL write time 17 ms
