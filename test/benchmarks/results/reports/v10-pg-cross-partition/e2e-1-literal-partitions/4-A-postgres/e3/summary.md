# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e/4-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.4 ms | 3.1 ms | 9.3 ms | 67.1 ms | 125.8 ms | 85.0 ms |

Load generator: 0.19 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.06 | 905 MB | 708 MB | 10 | 0.052 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.2% | 174.8% | 2.119GiB / 7.748GiB |
| wow-e2e-bench-postgres | 67.1% | 97.7% | 894.2MiB / 7.748GiB |

Store operations, postgres (seconds -0.056 to 40.946): {epoch=41.00156116485596, xact_commit=166185, xact_rollback=0, tup_inserted=84422, tup_updated=190967, tup_deleted=0, tup_fetched=238436, tup_returned=278522, blks_read=0, blks_hit=2207676, wal_records=845656, wal_fpi=0, wal_bytes=124672146, wal_buffers_full=0, wal_write=145906, wal_sync=145906, wal_write_time=682.703, wal_sync_time=23121.887000000002}
Store operations, kafkaEndOffsets (seconds -20.028 to 41.879): {wow.transfer.account.state=124584}

PostgreSQL per second: commits 4053, WAL flushes 3559, WAL flush time 564 ms (max 722 ms), WAL write time 17 ms
