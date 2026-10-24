# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache/1-large-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 1.1 ms | 2.8 ms | 4.4 ms | 27.9 ms | 52.7 ms | 44.5 ms |

Load generator: 0.16 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.08 | 1177 MB | 971 MB | 31 | 0.077 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.1% | 174.7% | 1.987GiB / 7.748GiB |
| wow-e2e-bench-postgres | 64.2% | 124.1% | 997.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.049 to 60.952): {epoch=61.000718116760254, xact_commit=284922, xact_rollback=0, tup_inserted=144842, tup_updated=321766, tup_deleted=9, tup_fetched=404991, tup_returned=476414, blks_read=12, blks_hit=3788844, wal_records=1447545, wal_fpi=86, wal_bytes=208192298, wal_buffers_full=0, wal_write=254012, wal_sync=254012, wal_write_time=929.0219999999999, wal_sync_time=34660.551999999996}
Store operations, kafkaEndOffsets (seconds -30.019 to 61.976): {wow.transfer.account.state=214025}

PostgreSQL per second: commits 4671, WAL flushes 4164, WAL flush time 568 ms (max 746 ms), WAL write time 15 ms
