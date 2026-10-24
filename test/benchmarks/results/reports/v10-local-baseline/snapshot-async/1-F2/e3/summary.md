# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 28, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/1-F2/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 2.3 ms | 5.5 ms | 12.5 ms | 50.4 ms | 78.1 ms | 62.2 ms |

Load generator: 0.22 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.45 | 923 MB | 795 MB | 17 | 0.098 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 63.2% | 184.6% | 1.992GiB / 7.748GiB |
| wow-e2e-bench-postgres | 109.9% | 226.8% | 909.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.044 to 60.958): {epoch=61.00205588340759, xact_commit=267891, xact_rollback=0, tup_inserted=144837, tup_updated=322038, tup_deleted=7, tup_fetched=406245, tup_returned=480058, blks_read=0, blks_hit=3820128, wal_records=1431648, wal_fpi=62, wal_bytes=208247523, wal_buffers_full=0, wal_write=177190, wal_sync=177190, wal_write_time=1404.4979999999996, wal_sync_time=44095.084}
Store operations, kafkaEndOffsets (seconds -30.005 to 62.053): {wow.transfer.account.state=212799}

PostgreSQL per second: commits 4392, WAL flushes 2905, WAL flush time 723 ms (max 834 ms), WAL write time 23 ms
