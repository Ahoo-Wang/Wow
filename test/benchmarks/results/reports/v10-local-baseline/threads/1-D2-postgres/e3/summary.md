# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/1-D2-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 2.2 ms | 4.1 ms | 7.3 ms | 54.3 ms | 86.6 ms | 74.2 ms |

Load generator: 0.19 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.30 | 1067 MB | 785 MB | 32 | 0.095 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.1% | 182.4% | 1.988GiB / 7.748GiB |
| wow-e2e-bench-postgres | 92.3% | 144.6% | 887.8MiB / 7.748GiB |

Store operations, postgres (seconds -0.057 to 60.945): {epoch=61.0023078918457, xact_commit=351326, xact_rollback=0, tup_inserted=144755, tup_updated=321557, tup_deleted=7, tup_fetched=934215, tup_returned=1008032, blks_read=0, blks_hit=5043078, wal_records=1440331, wal_fpi=71, wal_bytes=208173105, wal_buffers_full=0, wal_write=220293, wal_sync=220293, wal_write_time=1097.3390000000004, wal_sync_time=39631.94}
Store operations, kafkaEndOffsets (seconds -30.032 to 62.031): {wow.transfer.account.state=213955}

PostgreSQL per second: commits 5759, WAL flushes 3611, WAL flush time 650 ms (max 845 ms), WAL write time 18 ms
