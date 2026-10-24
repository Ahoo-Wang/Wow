# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 32, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/1-I/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 2.9 ms | 8.1 ms | 28.8 ms | 169.0 ms | 219.8 ms | 197.6 ms |

Load generator: 0.25 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.72 | 928 MB | 807 MB | 17 | 0.118 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 66.6% | 183.2% | 2.001GiB / 7.748GiB |
| wow-e2e-bench-postgres | 121.3% | 192.2% | 943.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.056 to 60.95): {epoch=61.00611686706543, xact_commit=260589, xact_rollback=0, tup_inserted=144378, tup_updated=319986, tup_deleted=12, tup_fetched=404895, tup_returned=477648, blks_read=12, blks_hit=3740800, wal_records=1425143, wal_fpi=42, wal_bytes=207787856, wal_buffers_full=0, wal_write=118669, wal_sync=118669, wal_write_time=1300.3360000000002, wal_sync_time=40901.94400000002}
Store operations, kafkaEndOffsets (seconds -30.032 to 62.143): {wow.transfer.account.state=213129}

PostgreSQL per second: commits 4272, WAL flushes 1945, WAL flush time 671 ms (max 868 ms), WAL write time 21 ms
