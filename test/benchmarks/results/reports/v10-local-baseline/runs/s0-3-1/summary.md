# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 36, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s0-up-3.

Open loop at 2542.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2542.0/s | 152520 | 0 | 13.2 ms | 102.4 ms | 188.4 ms | 397.3 ms | 599.3 ms | 440.3 ms |

Load generator: 0.72 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.19 | 1090 MB | 840 MB | 42 | 0.262 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 78.5% | 204.2% | 1.99GiB / 7.748GiB |
| wow-e2e-bench-postgres | 182.3% | 357.2% | 828.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.006 to 59.994): {epoch=60.000041007995605, xact_commit=286368, xact_rollback=0, tup_inserted=151993, tup_updated=313315, tup_deleted=7, tup_fetched=857383, tup_returned=961558, blks_read=0, blks_hit=5242748, wal_records=1395825, wal_fpi=6447, wal_bytes=224381072, wal_buffers_full=0, wal_write=78734, wal_sync=78734, wal_write_time=1720.0339999999997, wal_sync_time=50059.371}
Store operations, kafkaEndOffsets (seconds -29.666 to 62.314): {wow.transfer.account.state=223789}

PostgreSQL per second: commits 4771, WAL flushes 1312, WAL flush time 834 ms (max 948 ms), WAL write time 29 ms
