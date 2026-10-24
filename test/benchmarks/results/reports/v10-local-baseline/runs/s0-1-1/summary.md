# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s0-up-1.

Open loop at 2542.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2542.0/s | 152520 | 0 | 2.8 ms | 23.6 ms | 57.6 ms | 86.5 ms | 137.8 ms | 94.7 ms |

Load generator: 0.59 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.10 | 1033 MB | 850 MB | 38 | 0.217 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 62.2% | 185.1% | 2.078GiB / 7.748GiB |
| wow-e2e-bench-postgres | 159.3% | 206.6% | 1.014GiB / 7.748GiB |

Store operations, postgres (seconds -0.006 to 59.994): {epoch=59.99923086166382, xact_commit=354609, xact_rollback=0, tup_inserted=152620, tup_updated=334138, tup_deleted=9, tup_fetched=1466099, tup_returned=1551161, blks_read=1151, blks_hit=5891382, wal_records=1460086, wal_fpi=9447, wal_bytes=244330957, wal_buffers_full=0, wal_write=118319, wal_sync=118319, wal_write_time=1705.0149999999994, wal_sync_time=50026.103}
Store operations, kafkaEndOffsets (seconds -29.654 to 62.156): {wow.transfer.account.state=223727}

PostgreSQL per second: commits 5910, WAL flushes 1972, WAL flush time 834 ms (max 876 ms), WAL write time 28 ms
