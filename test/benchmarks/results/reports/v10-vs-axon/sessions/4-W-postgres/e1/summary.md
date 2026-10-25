# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 10, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon-2/4-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.5 ms | 1.7 ms | 2.8 ms | 12.5 ms | 20.2 ms | 14.5 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.99 | 1058 MB | 705 MB | 12 | 0.055 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.1% | 153.2% | 380.2MiB / 7.748GiB |
| wow-e2e-bench-postgres | 57.7% | 89.6% | 626.8MiB / 7.748GiB |

Store operations, postgres (seconds -0.996 to 40.005): {epoch=41.00107192993164, xact_commit=199691, xact_rollback=0, tup_inserted=102175, tup_updated=223434, tup_deleted=7, tup_fetched=283988, tup_returned=344002, blks_read=0, blks_hit=2526970, wal_records=1037349, wal_fpi=2, wal_bytes=124125429, wal_buffers_full=0, wal_write=185462, wal_sync=185462, wal_write_time=694.559, wal_sync_time=21066.639000000003}

PostgreSQL per second: commits 4870, WAL flushes 4523, WAL flush time 514 ms (max 729 ms), WAL write time 17 ms
