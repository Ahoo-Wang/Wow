# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 24, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/2-D2-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 2.4 ms | 4.8 ms | 11.4 ms | 50.9 ms | 75.6 ms | 67.6 ms |

Load generator: 0.19 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.46 | 1059 MB | 775 MB | 30 | 0.097 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.5% | 177.6% | 2.001GiB / 7.748GiB |
| wow-e2e-bench-postgres | 100.3% | 165.9% | 873.2MiB / 7.748GiB |

Store operations, postgres (seconds -0.056 to 60.945): {epoch=61.00074791908264, xact_commit=347763, xact_rollback=0, tup_inserted=144461, tup_updated=321136, tup_deleted=7, tup_fetched=923813, tup_returned=994493, blks_read=0, blks_hit=5028437, wal_records=1433901, wal_fpi=65, wal_bytes=207818353, wal_buffers_full=0, wal_write=206930, wal_sync=206930, wal_write_time=1297.3090000000002, wal_sync_time=41194.04799999998}
Store operations, kafkaEndOffsets (seconds -30.029 to 62.03): {wow.transfer.account.state=213539}

PostgreSQL per second: commits 5701, WAL flushes 3392, WAL flush time 675 ms (max 822 ms), WAL write time 21 ms
