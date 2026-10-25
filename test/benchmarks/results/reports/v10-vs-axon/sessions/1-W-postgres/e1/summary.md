# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon-2/1-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.5 ms | 3.0 ms | 69.1 ms | 290.8 ms | 361.8 ms | 327.7 ms |

Load generator: 0.46 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.02 | 874 MB | 731 MB | 12 | 0.077 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.4% | 179.2% | 398.5MiB / 7.748GiB |
| wow-e2e-bench-postgres | 65.3% | 159.8% | 623.6MiB / 7.748GiB |

Store operations, postgres (seconds -0.929 to 40.072): {epoch=41.001025915145874, xact_commit=191097, xact_rollback=0, tup_inserted=101675, tup_updated=221513, tup_deleted=7, tup_fetched=287911, tup_returned=502936, blks_read=0, blks_hit=2711414, wal_records=1028525, wal_fpi=3, wal_bytes=123430594, wal_buffers_full=0, wal_write=174007, wal_sync=174007, wal_write_time=725.179, wal_sync_time=21946.588}

PostgreSQL per second: commits 4661, WAL flushes 4244, WAL flush time 535 ms (max 759 ms), WAL write time 18 ms
