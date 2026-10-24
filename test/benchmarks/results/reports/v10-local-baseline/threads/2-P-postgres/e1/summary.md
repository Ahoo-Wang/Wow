# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 24, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/2-P-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.1 ms | 2.4 ms | 5.3 ms | 23.6 ms | 66.2 ms | 43.8 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.48 | 1146 MB | 841 MB | 27 | 0.128 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.8% | 169.3% | 1.975GiB / 7.748GiB |
| wow-e2e-bench-postgres | 80.7% | 124.8% | 789.8MiB / 7.748GiB |

Store operations, postgres (seconds -0.004 to 59.996): {epoch=59.99945592880249, xact_commit=309458, xact_rollback=0, tup_inserted=114357, tup_updated=260285, tup_deleted=7, tup_fetched=708405, tup_returned=782978, blks_read=0, blks_hit=4061394, wal_records=1154720, wal_fpi=6876, wal_bytes=186549489, wal_buffers_full=0, wal_write=195955, wal_sync=195955, wal_write_time=1220.3600000000001, wal_sync_time=36642.24100000001}
Store operations, kafkaEndOffsets (seconds -29.806 to 62.061): {wow.transfer.account.state=169421}

PostgreSQL per second: commits 5158, WAL flushes 3266, WAL flush time 611 ms (max 774 ms), WAL write time 20 ms
