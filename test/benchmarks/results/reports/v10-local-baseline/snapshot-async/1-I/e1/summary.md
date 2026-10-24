# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 32, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/1-I/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.1 ms | 3.3 ms | 7.1 ms | 20.6 ms | 39.7 ms | 29.7 ms |

Load generator: 0.42 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.54 | 912 MB | 781 MB | 16 | 0.094 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 64.0% | 191.7% | 2.043GiB / 7.748GiB |
| wow-e2e-bench-postgres | 84.5% | 149.0% | 709.9MiB / 7.748GiB |

Store operations, postgres (seconds 0.0 to 60.003): {epoch=60.003418922424316, xact_commit=225643, xact_rollback=0, tup_inserted=114177, tup_updated=260020, tup_deleted=7, tup_fetched=322660, tup_returned=397525, blks_read=0, blks_hit=2868567, wal_records=1150005, wal_fpi=6856, wal_bytes=185652526, wal_buffers_full=0, wal_write=108364, wal_sync=108364, wal_write_time=1103.6110000000003, wal_sync_time=29954.032000000007}
Store operations, kafkaEndOffsets (seconds -29.76 to 62.102): {wow.transfer.account.state=168436}

PostgreSQL per second: commits 3761, WAL flushes 1806, WAL flush time 499 ms (max 748 ms), WAL write time 18 ms
