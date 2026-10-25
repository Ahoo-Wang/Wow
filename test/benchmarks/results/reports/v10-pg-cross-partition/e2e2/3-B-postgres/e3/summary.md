# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 13, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e2/3-B-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.2 ms | 3.6 ms | 6.3 ms | 31.7 ms | 51.5 ms | 40.7 ms |

Load generator: 0.17 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.05 | 904 MB | 748 MB | 10 | 0.049 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.0% | 164.7% | 1.836GiB / 7.748GiB |
| wow-e2e-bench-postgres | 62.0% | 94.4% | 739.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.053 to 40.951): {epoch=41.003607988357544, xact_commit=166013, xact_rollback=0, tup_inserted=84516, tup_updated=191015, tup_deleted=0, tup_fetched=235975, tup_returned=281984, blks_read=0, blks_hit=2211346, wal_records=849008, wal_fpi=0, wal_bytes=124956321, wal_buffers_full=0, wal_write=152111, wal_sync=152111, wal_write_time=669.7040000000002, wal_sync_time=21756.756999999998}
Store operations, kafkaEndOffsets (seconds -20.027 to 41.898): {wow.transfer.account.state=124642}

PostgreSQL per second: commits 4049, WAL flushes 3710, WAL flush time 531 ms (max 707 ms), WAL write time 16 ms
