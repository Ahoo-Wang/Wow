# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 28, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/1-F2/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.9 ms | 8.5 ms | 94.7 ms | 299.0 ms | 410.8 ms | 344.1 ms |

Load generator: 0.57 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.68 | 942 MB | 760 MB | 16 | 0.130 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 75.7% | 192.0% | 1.933GiB / 7.748GiB |
| wow-e2e-bench-postgres | 117.7% | 216.7% | 722.5MiB / 7.748GiB |

Store operations, postgres (seconds -0.938 to 60.066): {epoch=61.004151821136475, xact_commit=210477, xact_rollback=0, tup_inserted=116361, tup_updated=263388, tup_deleted=7, tup_fetched=327454, tup_returned=405432, blks_read=0, blks_hit=2927182, wal_records=1149334, wal_fpi=6990, wal_bytes=187852011, wal_buffers_full=0, wal_write=133274, wal_sync=133274, wal_write_time=1819.7050000000004, wal_sync_time=47321.29000000001}
Store operations, kafkaEndOffsets (seconds -29.675 to 62.284): {wow.transfer.account.state=168309}

PostgreSQL per second: commits 3450, WAL flushes 2185, WAL flush time 776 ms (max 897 ms), WAL write time 30 ms
