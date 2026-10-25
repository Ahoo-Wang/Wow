# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e/4-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.1 ms | 4.5 ms | 100.9 ms | 239.6 ms | 285.2 ms | 270.3 ms |

Load generator: 0.48 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.55 | 1042 MB | 730 MB | 16 | 0.118 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 52.9% | 172.0% | 1.742GiB / 7.748GiB |
| wow-e2e-bench-postgres | 90.2% | 125.8% | 777.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.949 to 40.051): {epoch=41.00000596046448, xact_commit=190433, xact_rollback=0, tup_inserted=101728, tup_updated=223135, tup_deleted=0, tup_fetched=264341, tup_returned=328031, blks_read=0, blks_hit=2413195, wal_records=992425, wal_fpi=2, wal_bytes=120957329, wal_buffers_full=0, wal_write=139058, wal_sync=139058, wal_write_time=997.3679999999999, wal_sync_time=31303.896}
Store operations, kafkaEndOffsets (seconds -19.743 to 41.945): {wow.transfer.account.state=147104}

PostgreSQL per second: commits 4645, WAL flushes 3392, WAL flush time 764 ms (max 964 ms), WAL write time 24 ms
