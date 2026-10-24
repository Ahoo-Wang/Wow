# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 34, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/2-I/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 2.6 ms | 16.8 ms | 119.8 ms | 209.9 ms | 263.4 ms | 235.5 ms |

Load generator: 0.26 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.57 | 910 MB | 808 MB | 17 | 0.147 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 67.5% | 187.1% | 1.954GiB / 7.748GiB |
| wow-e2e-bench-postgres | 114.0% | 267.5% | 973MiB / 7.748GiB |

Store operations, postgres (seconds -0.054 to 60.947): {epoch=61.001590967178345, xact_commit=257980, xact_rollback=0, tup_inserted=144041, tup_updated=318290, tup_deleted=11, tup_fetched=401831, tup_returned=474987, blks_read=0, blks_hit=3732843, wal_records=1418698, wal_fpi=83, wal_bytes=207542598, wal_buffers_full=0, wal_write=118545, wal_sync=118545, wal_write_time=1168.6639999999998, wal_sync_time=37536.119999999995}
Store operations, kafkaEndOffsets (seconds -30.033 to 62.131): {wow.transfer.account.state=214063}

PostgreSQL per second: commits 4229, WAL flushes 1943, WAL flush time 615 ms (max 839 ms), WAL write time 19 ms
