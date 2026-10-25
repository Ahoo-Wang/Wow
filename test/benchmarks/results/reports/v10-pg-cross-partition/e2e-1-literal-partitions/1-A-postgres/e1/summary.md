# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e/1-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.7 ms | 2.8 ms | 25.0 ms | 113.7 ms | 154.3 ms | 135.2 ms |

Load generator: 0.49 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.35 | 1048 MB | 726 MB | 16 | 0.117 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.4% | 164.1% | 1.708GiB / 7.748GiB |
| wow-e2e-bench-postgres | 76.6% | 126.4% | 754.5MiB / 7.748GiB |

Store operations, postgres (seconds -0.932 to 40.073): {epoch=41.00560188293457, xact_commit=192947, xact_rollback=0, tup_inserted=100951, tup_updated=222256, tup_deleted=7, tup_fetched=263796, tup_returned=330467, blks_read=0, blks_hit=2475404, wal_records=1018262, wal_fpi=3, wal_bytes=122392603, wal_buffers_full=0, wal_write=162278, wal_sync=162278, wal_write_time=742.0360000000003, wal_sync_time=26478.664999999994}
Store operations, kafkaEndOffsets (seconds -19.755 to 41.846): {wow.transfer.account.state=146198}

PostgreSQL per second: commits 4706, WAL flushes 3958, WAL flush time 646 ms (max 892 ms), WAL write time 18 ms
