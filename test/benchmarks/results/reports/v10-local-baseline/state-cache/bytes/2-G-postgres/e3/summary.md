# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 42, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/2-G-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 7.9 ms | 108.5 ms | 177.2 ms | 242.7 ms | 316.0 ms | 262.1 ms |

Load generator: 0.32 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.70 | 919 MB | 812 MB | 16 | 0.194 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 81.3% | 204.3% | 1.98GiB / 7.748GiB |
| wow-e2e-bench-postgres | 159.7% | 288.7% | 1001MiB / 7.748GiB |

Store operations, postgres (seconds -0.056 to 60.944): {epoch=60.99973797798157, xact_commit=197452, xact_rollback=0, tup_inserted=144860, tup_updated=311687, tup_deleted=11, tup_fetched=395969, tup_returned=476644, blks_read=1, blks_hit=3762624, wal_records=1396320, wal_fpi=61, wal_bytes=208325894, wal_buffers_full=0, wal_write=85855, wal_sync=85855, wal_write_time=1766.2690000000002, wal_sync_time=50378.85799999998}
Store operations, kafkaEndOffsets (seconds -30.022 to 62.489): {wow.transfer.account.state=213417}

PostgreSQL per second: commits 3237, WAL flushes 1407, WAL flush time 826 ms (max 910 ms), WAL write time 29 ms
