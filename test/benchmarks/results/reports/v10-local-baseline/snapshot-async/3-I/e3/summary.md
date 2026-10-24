# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/3-I/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 1.7 ms | 4.1 ms | 7.3 ms | 43.5 ms | 68.5 ms | 61.2 ms |

Load generator: 0.22 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.48 | 925 MB | 807 MB | 17 | 0.094 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 59.6% | 177.3% | 1.991GiB / 7.748GiB |
| wow-e2e-bench-postgres | 88.1% | 158.9% | 920.5MiB / 7.748GiB |

Store operations, postgres (seconds -0.043 to 60.961): {epoch=61.00453996658325, xact_commit=277298, xact_rollback=0, tup_inserted=144441, tup_updated=321050, tup_deleted=7, tup_fetched=404573, tup_returned=473927, blks_read=0, blks_hit=3796852, wal_records=1436406, wal_fpi=70, wal_bytes=207923951, wal_buffers_full=0, wal_write=134403, wal_sync=134403, wal_write_time=894.3859999999995, wal_sync_time=30156.941000000006}
Store operations, kafkaEndOffsets (seconds -30.012 to 62.007): {wow.transfer.account.state=213013}

PostgreSQL per second: commits 4546, WAL flushes 2203, WAL flush time 494 ms (max 691 ms), WAL write time 15 ms
