# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 41, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-postgres-up.

Open loop at 1089.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1089.0/s | 65340 | 0 | 0.7 ms | 1.2 ms | 1.7 ms | 7.0 ms | 23.9 ms | 16.3 ms |

Load generator: 0.15 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.59 | 932 MB | 841 MB | 11 | 0.053 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 27.8% | 102.7% | 2.034GiB / 7.748GiB |
| wow-e2e-bench-postgres | 23.1% | 61.4% | 880.8MiB / 7.748GiB |

Store operations, postgres (seconds -0.047 to 60.948): {epoch=60.995311975479126, xact_commit=138942, xact_rollback=0, tup_inserted=65835, tup_updated=164100, tup_deleted=9, tup_fetched=317896, tup_returned=337095, blks_read=46163, blks_hit=1834249, wal_records=686421, wal_fpi=3210, wal_bytes=100724095, wal_buffers_full=0, wal_write=131194, wal_sync=131194, wal_write_time=424.1879999999983, wal_sync_time=10724.591000000015}
Store operations, kafkaEndOffsets (seconds -30.009 to 61.955): {wow.transfer.account.state=109357}

PostgreSQL per second: commits 2278, WAL flushes 2151, WAL flush time 176 ms (max 214 ms), WAL write time 7 ms
