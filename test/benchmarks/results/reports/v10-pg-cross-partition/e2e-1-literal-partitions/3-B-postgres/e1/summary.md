# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 14, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e/3-B-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 4.4 ms | 8.9 ms | 13.1 ms | 38.4 ms | 74.9 ms | 59.1 ms |

Load generator: 0.57 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.56 | 920 MB | 771 MB | 16 | 0.094 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 61.0% | 177.6% | 1.822GiB / 7.748GiB |
| wow-e2e-bench-postgres | 178.9% | 216.6% | 390MiB / 7.748GiB |

Store operations, postgres (seconds -0.951 to 40.05): {epoch=41.000909090042114, xact_commit=109083, xact_rollback=0, tup_inserted=102149, tup_updated=224519, tup_deleted=7, tup_fetched=418236, tup_returned=483464, blks_read=0, blks_hit=2880866, wal_records=1040935, wal_fpi=3, wal_bytes=126205173, wal_buffers_full=0, wal_write=82192, wal_sync=82192, wal_write_time=777.7249999999999, wal_sync_time=22828.022}
Store operations, kafkaEndOffsets (seconds -19.771 to 41.924): {wow.transfer.account.state=146692}

PostgreSQL per second: commits 2660, WAL flushes 2005, WAL flush time 557 ms (max 627 ms), WAL write time 19 ms
