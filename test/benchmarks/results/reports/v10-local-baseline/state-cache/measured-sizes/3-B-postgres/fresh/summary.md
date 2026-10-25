# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 19, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/3-B-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.5 ms | 1.8 ms | 3.6 ms | 19.7 ms | 45.2 ms | 31.6 ms |

Load generator: 0.32 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.01 | 982 MB | 740 MB | 16 | 0.069 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.4% | 174.9% | 1.942GiB / 7.748GiB |
| wow-e2e-bench-postgres | 55.0% | 108.0% | 801MiB / 7.748GiB |

Store operations, postgres (seconds -0.989 to 60.012): {epoch=61.00089502334595, xact_commit=230162, xact_rollback=0, tup_inserted=116679, tup_updated=264778, tup_deleted=7, tup_fetched=312935, tup_returned=389035, blks_read=0, blks_hit=2842790, wal_records=1174314, wal_fpi=2, wal_bytes=140342316, wal_buffers_full=0, wal_write=212668, wal_sync=212668, wal_write_time=1042.497, wal_sync_time=28054.592999999993}
Store operations, kafkaEndOffsets (seconds -29.765 to 61.963): {wow.transfer.account.state=168754}

PostgreSQL per second: commits 3773, WAL flushes 3486, WAL flush time 460 ms (max 709 ms), WAL write time 17 ms
