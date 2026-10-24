# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/1-F-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.2 ms | 3.3 ms | 6.9 ms | 41.2 ms | 94.5 ms | 65.0 ms |

Load generator: 0.41 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.35 | 1276 MB | 860 MB | 26 | 0.110 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.4% | 186.7% | 2.067GiB / 7.748GiB |
| wow-e2e-bench-postgres | 89.2% | 163.1% | 752.6MiB / 7.748GiB |

Store operations, postgres (seconds -0.997 to 60.003): {epoch=61.00002193450928, xact_commit=311998, xact_rollback=0, tup_inserted=116392, tup_updated=264931, tup_deleted=7, tup_fetched=723616, tup_returned=800117, blks_read=0, blks_hit=4129562, wal_records=1169480, wal_fpi=7007, wal_bytes=188717466, wal_buffers_full=0, wal_write=187781, wal_sync=187781, wal_write_time=1149.37, wal_sync_time=37038.55}
Store operations, kafkaEndOffsets (seconds -29.669 to 62.048): {wow.transfer.account.state=168761}

PostgreSQL per second: commits 5115, WAL flushes 3078, WAL flush time 607 ms (max 806 ms), WAL write time 19 ms
