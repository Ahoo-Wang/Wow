# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon-2/2-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 4.3 ms | 7.5 ms | 20.2 ms | 36.1 ms | 49.3 ms | 32.5 ms |

Load generator: 0.16 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.02 | 1116 MB | 808 MB | 16 | 0.369 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 44.8% | 171.0% | 396.8MiB / 7.748GiB |
| wow-e2e-bench-postgres | 84.8% | 103.6% | 353.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.046 to 40.956): {epoch=41.002753019332886, xact_commit=151112, xact_rollback=0, tup_inserted=112464, tup_updated=57781, tup_deleted=6, tup_fetched=630184, tup_returned=770978, blks_read=1, blks_hit=3233569, wal_records=606432, wal_fpi=1, wal_bytes=89578795, wal_buffers_full=0, wal_write=79358, wal_sync=79358, wal_write_time=590.1790000000001, wal_sync_time=17357.770000000004}

PostgreSQL per second: commits 3685, WAL flushes 1936, WAL flush time 423 ms (max 575 ms), WAL write time 14 ms
