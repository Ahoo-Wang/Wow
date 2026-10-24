# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 30, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/1-G-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 1.6 ms | 3.7 ms | 7.5 ms | 36.9 ms | 75.5 ms | 62.7 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.43 | 942 MB | 807 MB | 16 | 0.082 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.9% | 180.8% | 2.124GiB / 7.748GiB |
| wow-e2e-bench-postgres | 84.2% | 173.0% | 923.8MiB / 7.748GiB |

Store operations, postgres (seconds -0.054 to 60.947): {epoch=61.0011031627655, xact_commit=277796, xact_rollback=0, tup_inserted=144536, tup_updated=321467, tup_deleted=7, tup_fetched=405274, tup_returned=475560, blks_read=0, blks_hit=3733893, wal_records=1439946, wal_fpi=86, wal_bytes=208489789, wal_buffers_full=0, wal_write=218485, wal_sync=218485, wal_write_time=1167.8249999999998, wal_sync_time=39882.59400000001}
Store operations, kafkaEndOffsets (seconds -30.028 to 62.003): {wow.transfer.account.state=214142}

PostgreSQL per second: commits 4554, WAL flushes 3582, WAL flush time 654 ms (max 830 ms), WAL write time 19 ms
