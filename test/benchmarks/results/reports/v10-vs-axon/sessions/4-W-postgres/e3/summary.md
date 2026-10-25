# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 10, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon-2/4-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.0 ms | 2.4 ms | 4.0 ms | 15.1 ms | 27.7 ms | 19.7 ms |

Load generator: 0.12 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.72 | 912 MB | 739 MB | 8 | 0.035 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.4% | 158.6% | 505.1MiB / 7.748GiB |
| wow-e2e-bench-postgres | 48.5% | 116.7% | 736.6MiB / 7.748GiB |

Store operations, postgres (seconds -0.054 to 40.947): {epoch=41.000648021698, xact_commit=168471, xact_rollback=0, tup_inserted=84591, tup_updated=189045, tup_deleted=7, tup_fetched=234028, tup_returned=281396, blks_read=0, blks_hit=2247475, wal_records=853150, wal_fpi=0, wal_bytes=124852522, wal_buffers_full=0, wal_write=160380, wal_sync=160380, wal_write_time=569.1779999999999, wal_sync_time=18042.392}

PostgreSQL per second: commits 4109, WAL flushes 3912, WAL flush time 440 ms (max 597 ms), WAL write time 14 ms
