# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 42, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/2-G-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 2.7 ms | 22.7 ms | 57.3 ms | 185.3 ms | 244.8 ms | 223.2 ms |

Load generator: 0.57 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.76 | 908 MB | 781 MB | 16 | 0.191 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 74.1% | 173.5% | 1.94GiB / 7.748GiB |
| wow-e2e-bench-postgres | 129.8% | 203.1% | 737.7MiB / 7.748GiB |

Store operations, postgres (seconds -0.945 to 60.055): {epoch=61.00021290779114, xact_commit=197805, xact_rollback=0, tup_inserted=116626, tup_updated=263406, tup_deleted=7, tup_fetched=326288, tup_returned=405083, blks_read=0, blks_hit=2929635, wal_records=1176586, wal_fpi=6223, wal_bytes=186765017, wal_buffers_full=0, wal_write=106251, wal_sync=106251, wal_write_time=2062.9170000000004, wal_sync_time=49695.97700000001}
Store operations, kafkaEndOffsets (seconds -29.703 to 62.374): {wow.transfer.account.state=168509}

PostgreSQL per second: commits 3243, WAL flushes 1742, WAL flush time 815 ms (max 896 ms), WAL write time 34 ms
