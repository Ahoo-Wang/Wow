# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 41, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-postgres-up.

Open loop at 2177.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2177.0/s | 130620 | 0 | 0.7 ms | 1.9 ms | 3.7 ms | 19.8 ms | 61.7 ms | 41.7 ms |

Load generator: 0.35 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.27 | 1031 MB | 817 MB | 19 | 0.057 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.9% | 155.2% | 2.053GiB / 7.748GiB |
| wow-e2e-bench-postgres | 65.7% | 95.1% | 882.6MiB / 7.748GiB |

Store operations, postgres (seconds -0.042 to 60.96): {epoch=61.00184607505798, xact_commit=269568, xact_rollback=0, tup_inserted=131991, tup_updated=296152, tup_deleted=0, tup_fetched=549801, tup_returned=571052, blks_read=57460, blks_hit=3405628, wal_records=1304430, wal_fpi=1447, wal_bytes=167939549, wal_buffers_full=0, wal_write=228920, wal_sync=228920, wal_write_time=1133.9039999999986, wal_sync_time=34510.08600000001}
Store operations, kafkaEndOffsets (seconds -30.008 to 62.051): {wow.transfer.account.state=183894}

PostgreSQL per second: commits 4419, WAL flushes 3753, WAL flush time 566 ms (max 751 ms), WAL write time 19 ms
