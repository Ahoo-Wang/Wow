# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 27, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/3-F2/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.7 ms | 2.0 ms | 3.6 ms | 14.7 ms | 38.3 ms | 29.3 ms |

Load generator: 0.34 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.13 | 884 MB | 783 MB | 16 | 0.086 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 52.3% | 181.2% | 1.908GiB / 7.748GiB |
| wow-e2e-bench-postgres | 65.2% | 132.0% | 724.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.001 to 60.0): {epoch=60.00188183784485, xact_commit=230562, xact_rollback=0, tup_inserted=113933, tup_updated=260062, tup_deleted=7, tup_fetched=323356, tup_returned=398309, blks_read=0, blks_hit=2865859, wal_records=1151515, wal_fpi=6971, wal_bytes=186094213, wal_buffers_full=0, wal_write=204335, wal_sync=204335, wal_write_time=1157.8929999999996, wal_sync_time=30721.367}
Store operations, kafkaEndOffsets (seconds -29.795 to 61.992): {wow.transfer.account.state=169282}

PostgreSQL per second: commits 3843, WAL flushes 3405, WAL flush time 512 ms (max 717 ms), WAL write time 19 ms
