# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e2/4-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.9 ms | 2.0 ms | 11.8 ms | 148.5 ms | 184.9 ms | 162.8 ms |

Load generator: 0.49 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.55 | 936 MB | 737 MB | 14 | 0.073 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.9% | 177.9% | 1.728GiB / 7.748GiB |
| wow-e2e-bench-postgres | 81.4% | 128.0% | 758.6MiB / 7.748GiB |

Store operations, postgres (seconds -0.008 to 39.992): {epoch=40.00088596343994, xact_commit=190688, xact_rollback=0, tup_inserted=99702, tup_updated=219677, tup_deleted=0, tup_fetched=259922, tup_returned=322451, blks_read=0, blks_hit=2369262, wal_records=978354, wal_fpi=2, wal_bytes=118736576, wal_buffers_full=0, wal_write=150437, wal_sync=150437, wal_write_time=946.8979999999997, wal_sync_time=29192.221000000005}
Store operations, kafkaEndOffsets (seconds -19.795 to 41.925): {wow.transfer.account.state=146511}

PostgreSQL per second: commits 4767, WAL flushes 3760, WAL flush time 730 ms (max 911 ms), WAL write time 24 ms
