# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 31, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache/2-large-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 2.7 ms | 6.9 ms | 20.6 ms | 123.9 ms | 186.2 ms | 166.9 ms |

Load generator: 0.23 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.52 | 1167 MB | 971 MB | 30 | 0.101 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 66.9% | 179.6% | 1.872GiB / 7.748GiB |
| wow-e2e-bench-postgres | 119.5% | 205.2% | 1.013GiB / 7.748GiB |

Store operations, postgres (seconds -0.019 to 60.981): {epoch=61.00003695487976, xact_commit=259902, xact_rollback=0, tup_inserted=144006, tup_updated=319656, tup_deleted=12, tup_fetched=402571, tup_returned=474520, blks_read=0, blks_hit=3745943, wal_records=1444050, wal_fpi=37, wal_bytes=208777105, wal_buffers_full=0, wal_write=158739, wal_sync=158739, wal_write_time=1562.1150000000007, wal_sync_time=45279.157999999996}
Store operations, kafkaEndOffsets (seconds -29.932 to 62.125): {wow.transfer.account.state=211821}

PostgreSQL per second: commits 4261, WAL flushes 2602, WAL flush time 742 ms (max 844 ms), WAL write time 26 ms
