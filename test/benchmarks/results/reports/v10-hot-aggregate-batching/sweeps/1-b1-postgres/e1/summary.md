# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 27, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/1-b1-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.1 ms | 24.1 ms | 82.4 ms | 162.8 ms | 257.3 ms | 194.6 ms |

Load generator: 0.40 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.35 | 863 MB | 750 MB | 17 | 0.166 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 56.1% | 178.9% | 2.17GiB / 7.748GiB |
| wow-e2e-bench-postgres | 83.6% | 200.0% | 962MiB / 7.748GiB |

Store operations, postgres (seconds -0.971 to 60.029): {epoch=60.99949097633362, xact_commit=223319, xact_rollback=0, tup_inserted=116257, tup_updated=263013, tup_deleted=9, tup_fetched=380355, tup_returned=431623, blks_read=14124, blks_hit=3010953, wal_records=1165879, wal_fpi=7758, wal_bytes=189740837, wal_buffers_full=0, wal_write=171522, wal_sync=171522, wal_write_time=1258.0009999999993, wal_sync_time=38039.669999999984}
Store operations, kafkaEndOffsets (seconds -29.561 to 62.175): {wow.transfer.account.state=187455}

PostgreSQL per second: commits 3661, WAL flushes 2812, WAL flush time 624 ms (max 864 ms), WAL write time 21 ms
