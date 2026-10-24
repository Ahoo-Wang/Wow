# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/1-D2-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.9 ms | 2.2 ms | 4.3 ms | 25.2 ms | 54.0 ms | 46.6 ms |

Load generator: 0.32 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.14 | 1208 MB | 827 MB | 35 | 0.103 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.3% | 171.7% | 1.915GiB / 7.748GiB |
| wow-e2e-bench-postgres | 71.0% | 135.5% | 711MiB / 7.748GiB |

Store operations, postgres (seconds -0.981 to 60.029): {epoch=61.01009202003479, xact_commit=315109, xact_rollback=0, tup_inserted=115996, tup_updated=264157, tup_deleted=7, tup_fetched=729101, tup_returned=805289, blks_read=0, blks_hit=4119521, wal_records=1170671, wal_fpi=7064, wal_bytes=188784761, wal_buffers_full=0, wal_write=208443, wal_sync=208443, wal_write_time=952.393, wal_sync_time=31298.953999999998}
Store operations, kafkaEndOffsets (seconds -29.808 to 62.024): {wow.transfer.account.state=168970}

PostgreSQL per second: commits 5165, WAL flushes 3417, WAL flush time 513 ms (max 731 ms), WAL write time 16 ms
