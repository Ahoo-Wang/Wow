# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 41, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-postgres-up.

Open loop at 2858.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2759.9/s | 165591 | 5889 | 135.2 ms | 2007.0 ms | 2981.9 ms | 3571.7 ms | 4305.3 ms | 3850.2 ms |

Errors: {exception-SocketException=673, http-429-wow.command.busy=5216}

Load generator: 0.91 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.95 | 1246 MB | 1024 MB | 51 | 0.929 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 87.8% | 294.3% | 2.004GiB / 7.748GiB |
| wow-e2e-bench-postgres | 227.8% | 408.3% | 891MiB / 7.748GiB |

Store operations, postgres (seconds -0.047 to 60.952): {epoch=60.9981529712677, xact_commit=222492, xact_rollback=0, tup_inserted=166792, tup_updated=272047, tup_deleted=9, tup_fetched=1690801, tup_returned=1764492, blks_read=8184, blks_hit=6298563, wal_records=1384891, wal_fpi=156, wal_bytes=196297220, wal_buffers_full=0, wal_write=50124, wal_sync=50124, wal_write_time=1326.1499999999996, wal_sync_time=39038.951}
Store operations, kafkaEndOffsets (seconds -30.017 to 62.177): {wow.transfer.account.state=246082}

PostgreSQL per second: commits 3645, WAL flushes 821, WAL flush time 640 ms (max 835 ms), WAL write time 22 ms
