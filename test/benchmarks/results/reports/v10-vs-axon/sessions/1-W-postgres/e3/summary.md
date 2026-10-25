# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon-2/1-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.0 ms | 2.3 ms | 3.7 ms | 18.2 ms | 34.8 ms | 26.5 ms |

Load generator: 0.12 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.72 | 889 MB | 735 MB | 7 | 0.035 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.5% | 151.1% | 380.4MiB / 7.748GiB |
| wow-e2e-bench-postgres | 47.3% | 106.9% | 733.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.058 to 40.944): {epoch=41.00126504898071, xact_commit=167912, xact_rollback=0, tup_inserted=84377, tup_updated=187831, tup_deleted=7, tup_fetched=232828, tup_returned=279157, blks_read=0, blks_hit=2236408, wal_records=848140, wal_fpi=0, wal_bytes=124683926, wal_buffers_full=0, wal_write=160614, wal_sync=160614, wal_write_time=556.2169999999999, wal_sync_time=17477.276000000005}

PostgreSQL per second: commits 4095, WAL flushes 3917, WAL flush time 426 ms (max 537 ms), WAL write time 14 ms
