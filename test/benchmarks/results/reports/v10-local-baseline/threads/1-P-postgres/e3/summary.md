# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 25, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/1-P-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 2.6 ms | 5.0 ms | 12.4 ms | 105.0 ms | 190.7 ms | 146.4 ms |

Load generator: 0.20 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.64 | 1080 MB | 864 MB | 24 | 0.112 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.1% | 172.8% | 1.992GiB / 7.748GiB |
| wow-e2e-bench-postgres | 103.6% | 175.5% | 974MiB / 7.748GiB |

Store operations, postgres (seconds -0.061 to 60.942): {epoch=61.00304698944092, xact_commit=345611, xact_rollback=0, tup_inserted=144488, tup_updated=320919, tup_deleted=9, tup_fetched=924097, tup_returned=994497, blks_read=0, blks_hit=5030300, wal_records=1434810, wal_fpi=83, wal_bytes=207557417, wal_buffers_full=0, wal_write=194808, wal_sync=194808, wal_write_time=1215.3890000000001, wal_sync_time=42874.41699999999}
Store operations, kafkaEndOffsets (seconds -30.031 to 62.082): {wow.transfer.account.state=213970}

PostgreSQL per second: commits 5665, WAL flushes 3193, WAL flush time 703 ms (max 807 ms), WAL write time 20 ms
