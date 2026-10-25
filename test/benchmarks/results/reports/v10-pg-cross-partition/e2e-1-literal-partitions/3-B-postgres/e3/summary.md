# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 14, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e/3-B-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 6.5 ms | 12.5 ms | 17.2 ms | 41.2 ms | 62.6 ms | 55.6 ms |

Load generator: 0.23 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.18 | 914 MB | 785 MB | 11 | 0.065 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.1% | 185.1% | 1.754GiB / 7.748GiB |
| wow-e2e-bench-postgres | 134.5% | 178.8% | 499.2MiB / 7.748GiB |

Store operations, postgres (seconds -0.057 to 40.945): {epoch=41.00207185745239, xact_commit=129310, xact_rollback=0, tup_inserted=84786, tup_updated=190063, tup_deleted=0, tup_fetched=314909, tup_returned=359230, blks_read=0, blks_hit=2415612, wal_records=856500, wal_fpi=0, wal_bytes=126097284, wal_buffers_full=0, wal_write=110786, wal_sync=110786, wal_write_time=709.5589999999997, wal_sync_time=21516.661000000007}
Store operations, kafkaEndOffsets (seconds -20.032 to 41.845): {wow.transfer.account.state=124516}

PostgreSQL per second: commits 3154, WAL flushes 2702, WAL flush time 525 ms (max 611 ms), WAL write time 17 ms
