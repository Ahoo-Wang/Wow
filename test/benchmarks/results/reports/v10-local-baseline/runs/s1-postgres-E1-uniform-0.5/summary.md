# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 41, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-postgres-up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 4.4 ms | 58.1 ms | 106.5 ms | 151.6 ms | 214.9 ms | 169.0 ms |

Load generator: 0.57 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.97 | 1021 MB | 826 MB | 34 | 0.238 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 73.9% | 206.6% | 2.115GiB / 7.748GiB |
| wow-e2e-bench-postgres | 163.8% | 259.2% | 1.012GiB / 7.748GiB |

Store operations, postgres (seconds -0.045 to 60.955): {epoch=60.999935150146484, xact_commit=259855, xact_rollback=0, tup_inserted=115014, tup_updated=257108, tup_deleted=9, tup_fetched=1019142, tup_returned=1092493, blks_read=0, blks_hit=4378314, wal_records=1135993, wal_fpi=8338, wal_bytes=195616861, wal_buffers_full=0, wal_write=91246, wal_sync=91246, wal_write_time=1922.8809999999994, wal_sync_time=49112.17200000002}
Store operations, kafkaEndOffsets (seconds -30.017 to 62.336): {wow.transfer.account.state=168249}

PostgreSQL per second: commits 4260, WAL flushes 1496, WAL flush time 805 ms (max 877 ms), WAL write time 32 ms
