# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 24, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/2-P-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 2.6 ms | 5.0 ms | 11.5 ms | 57.9 ms | 83.2 ms | 71.2 ms |

Load generator: 0.19 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.64 | 1101 MB | 840 MB | 26 | 0.116 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 52.7% | 180.9% | 2.039GiB / 7.748GiB |
| wow-e2e-bench-postgres | 103.5% | 181.9% | 967MiB / 7.748GiB |

Store operations, postgres (seconds -0.056 to 60.943): {epoch=60.99894618988037, xact_commit=346245, xact_rollback=0, tup_inserted=144694, tup_updated=321369, tup_deleted=7, tup_fetched=924390, tup_returned=995263, blks_read=0, blks_hit=5036052, wal_records=1437272, wal_fpi=90, wal_bytes=207878787, wal_buffers_full=0, wal_write=194360, wal_sync=194360, wal_write_time=1379.321, wal_sync_time=43074.820999999996}
Store operations, kafkaEndOffsets (seconds -30.026 to 62.064): {wow.transfer.account.state=214198}

PostgreSQL per second: commits 5676, WAL flushes 3186, WAL flush time 706 ms (max 794 ms), WAL write time 23 ms
