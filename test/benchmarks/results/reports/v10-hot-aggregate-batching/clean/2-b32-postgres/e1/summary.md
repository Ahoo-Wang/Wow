# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 23, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch-clean/2-b32-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.6 ms | 1.6 ms | 3.0 ms | 26.4 ms | 73.1 ms | 55.3 ms |

Load generator: 0.32 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.06 | 1034 MB | 769 MB | 17 | 0.085 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.4% | 172.3% | 1.752GiB / 7.748GiB |
| wow-e2e-bench-postgres | 52.2% | 124.2% | 667MiB / 7.748GiB |

Store operations, postgres (seconds -0.994 to 60.007): {epoch=61.000701904296875, xact_commit=235454, xact_rollback=0, tup_inserted=116169, tup_updated=264836, tup_deleted=7, tup_fetched=328033, tup_returned=403763, blks_read=0, blks_hit=2901216, wal_records=1170302, wal_fpi=2, wal_bytes=139910111, wal_buffers_full=0, wal_write=215031, wal_sync=215030, wal_write_time=994.5409999999999, wal_sync_time=28409.74199999999}
Store operations, kafkaEndOffsets (seconds -29.829 to 62.056): {wow.transfer.account.state=169482}

PostgreSQL per second: commits 3860, WAL flushes 3525, WAL flush time 466 ms (max 728 ms), WAL write time 16 ms
