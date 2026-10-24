# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 31, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache/2-large-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.7 ms | 9.8 ms | 45.8 ms | 167.9 ms | 254.7 ms | 203.8 ms |

Load generator: 0.52 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.61 | 1178 MB | 966 MB | 30 | 0.137 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 69.2% | 184.5% | 1.792GiB / 7.748GiB |
| wow-e2e-bench-postgres | 107.1% | 199.6% | 846.5MiB / 7.748GiB |

Store operations, postgres (seconds -0.001 to 59.999): {epoch=60.000064849853516, xact_commit=204734, xact_rollback=0, tup_inserted=114471, tup_updated=258981, tup_deleted=7, tup_fetched=306708, tup_returned=382540, blks_read=0, blks_hit=2811397, wal_records=1154531, wal_fpi=6918, wal_bytes=186339864, wal_buffers_full=0, wal_write=139766, wal_sync=139766, wal_write_time=1825.4519999999998, wal_sync_time=46052.2}
Store operations, kafkaEndOffsets (seconds -29.743 to 62.23): {wow.transfer.account.state=168223}

PostgreSQL per second: commits 3412, WAL flushes 2329, WAL flush time 768 ms (max 885 ms), WAL write time 30 ms
