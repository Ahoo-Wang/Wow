# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 38, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache/2-default-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 190.5 ms | 974.8 ms | 1163.3 ms | 1335.3 ms | 1541.7 ms | 1376.3 ms |

Load generator: 0.32 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.56 | 1022 MB | 856 MB | 32 | 0.393 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 85.0% | 216.9% | 1.852GiB / 7.748GiB |
| wow-e2e-bench-postgres | 207.9% | 339.2% | 743MiB / 7.748GiB |

Store operations, postgres (seconds -0.05 to 60.925): {epoch=60.9746150970459, xact_commit=191591, xact_rollback=0, tup_inserted=145875, tup_updated=259672, tup_deleted=19, tup_fetched=845340, tup_returned=915915, blks_read=12, blks_hit=4771734, wal_records=1235783, wal_fpi=123, wal_bytes=201538363, wal_buffers_full=0, wal_write=61837, wal_sync=61837, wal_write_time=1422.973, wal_sync_time=47459.340999999986}
Store operations, kafkaEndOffsets (seconds -30.034 to 62.456): {wow.transfer.account.state=213495}

PostgreSQL per second: commits 3141, WAL flushes 1014, WAL flush time 778 ms (max 870 ms), WAL write time 23 ms
