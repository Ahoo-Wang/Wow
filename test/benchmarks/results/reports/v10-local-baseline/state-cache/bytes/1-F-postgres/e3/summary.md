# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/1-F-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 3.4 ms | 7.6 ms | 13.2 ms | 52.2 ms | 80.0 ms | 63.2 ms |

Load generator: 0.23 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.56 | 1111 MB | 825 MB | 25 | 0.104 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 62.1% | 179.8% | 2.076GiB / 7.748GiB |
| wow-e2e-bench-postgres | 129.6% | 206.9% | 937.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.049 to 60.951): {epoch=61.00057005882263, xact_commit=334857, xact_rollback=0, tup_inserted=144537, tup_updated=321240, tup_deleted=7, tup_fetched=926094, tup_returned=997200, blks_read=0, blks_hit=5036732, wal_records=1421458, wal_fpi=64, wal_bytes=207533895, wal_buffers_full=0, wal_write=161096, wal_sync=161096, wal_write_time=1477.5200000000004, wal_sync_time=45829.807}
Store operations, kafkaEndOffsets (seconds -30.009 to 62.073): {wow.transfer.account.state=213341}

PostgreSQL per second: commits 5489, WAL flushes 2641, WAL flush time 751 ms (max 878 ms), WAL write time 24 ms
