# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 27, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/3-F2/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 1.6 ms | 3.7 ms | 6.2 ms | 26.4 ms | 49.0 ms | 42.5 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.31 | 933 MB | 806 MB | 17 | 0.094 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.7% | 178.2% | 1.986GiB / 7.748GiB |
| wow-e2e-bench-postgres | 87.4% | 161.5% | 902.5MiB / 7.748GiB |

Store operations, postgres (seconds -0.054 to 60.95): {epoch=61.00344109535217, xact_commit=277775, xact_rollback=0, tup_inserted=144529, tup_updated=321418, tup_deleted=7, tup_fetched=405004, tup_returned=476054, blks_read=0, blks_hit=3803438, wal_records=1436329, wal_fpi=68, wal_bytes=207856652, wal_buffers_full=0, wal_write=219236, wal_sync=219236, wal_write_time=1180.7489999999998, wal_sync_time=39694.851999999984}
Store operations, kafkaEndOffsets (seconds -30.022 to 61.995): {wow.transfer.account.state=214257}

PostgreSQL per second: commits 4554, WAL flushes 3594, WAL flush time 651 ms (max 813 ms), WAL write time 19 ms
