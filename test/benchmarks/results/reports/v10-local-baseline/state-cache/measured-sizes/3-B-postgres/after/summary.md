# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 19, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/3-B-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 2.0 ms | 25.9 ms | 80.9 ms | 178.2 ms | 239.9 ms | 201.7 ms |

Load generator: 0.51 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.52 | 982 MB | 843 MB | 19 | 0.237 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 77.4% | 187.6% | 2.036GiB / 7.748GiB |
| wow-e2e-bench-postgres | 120.2% | 201.3% | 743.5MiB / 7.748GiB |

Store operations, postgres (seconds -0.941 to 60.06): {epoch=61.00083804130554, xact_commit=196759, xact_rollback=0, tup_inserted=115787, tup_updated=260681, tup_deleted=0, tup_fetched=349331, tup_returned=383464, blks_read=49, blks_hit=2888314, wal_records=1135132, wal_fpi=140, wal_bytes=139206715, wal_buffers_full=0, wal_write=128429, wal_sync=128429, wal_write_time=1638.8049999999985, wal_sync_time=47081.32699999999}
Store operations, kafkaEndOffsets (seconds -29.979 to 62.273): {wow.transfer.account.state=168731}

PostgreSQL per second: commits 3225, WAL flushes 2105, WAL flush time 772 ms (max 914 ms), WAL write time 27 ms
