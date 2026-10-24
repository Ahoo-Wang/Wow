# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 32, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/2-F-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 2.7 ms | 61.4 ms | 146.4 ms | 224.3 ms | 335.9 ms | 268.3 ms |

Load generator: 0.51 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.80 | 1049 MB | 820 MB | 28 | 0.204 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 71.2% | 187.6% | 1.882GiB / 7.748GiB |
| wow-e2e-bench-postgres | 143.4% | 245.9% | 762.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.947 to 60.055): {epoch=61.00160193443298, xact_commit=277769, xact_rollback=0, tup_inserted=116181, tup_updated=260138, tup_deleted=7, tup_fetched=705973, tup_returned=782985, blks_read=0, blks_hit=4103611, wal_records=1133300, wal_fpi=6959, wal_bytes=187014849, wal_buffers_full=0, wal_write=120345, wal_sync=120345, wal_write_time=1735.5170000000003, wal_sync_time=46870.70599999999}
Store operations, kafkaEndOffsets (seconds -29.687 to 62.243): {wow.transfer.account.state=168750}

PostgreSQL per second: commits 4553, WAL flushes 1973, WAL flush time 768 ms (max 906 ms), WAL write time 28 ms
