# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e2/4-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.2 ms | 3.1 ms | 15.2 ms | 105.5 ms | 148.4 ms | 120.8 ms |

Load generator: 0.16 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.04 | 1067 MB | 706 MB | 10 | 0.047 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.7% | 186.9% | 1.867GiB / 7.748GiB |
| wow-e2e-bench-postgres | 60.9% | 101.5% | 871.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.047 to 40.956): {epoch=41.00295400619507, xact_commit=165810, xact_rollback=0, tup_inserted=84491, tup_updated=190653, tup_deleted=0, tup_fetched=237790, tup_returned=278302, blks_read=0, blks_hit=2206062, wal_records=844441, wal_fpi=0, wal_bytes=124259869, wal_buffers_full=0, wal_write=149458, wal_sync=149458, wal_write_time=682.3379999999997, wal_sync_time=21439.954999999987}
Store operations, kafkaEndOffsets (seconds -20.018 to 41.88): {wow.transfer.account.state=124337}

PostgreSQL per second: commits 4044, WAL flushes 3645, WAL flush time 523 ms (max 812 ms), WAL write time 17 ms
