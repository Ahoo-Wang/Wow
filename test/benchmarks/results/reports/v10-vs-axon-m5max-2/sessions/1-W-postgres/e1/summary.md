# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/sessions/1-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 2.5 ms | 4.4 ms | 10.4 ms | 24.9 ms | 17.0 ms |

Load generator: 0.39 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.12 | 1257 MB | 734 MB | 14 | 0.035 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 29.7% | 119.5% | 501.3MiB / 31.36GiB |
| wow-e2e-bench-postgres | 57.5% | 98.6% | 626.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.052 to 40.953): {epoch=41.00494194030762, xact_commit=166024, xact_rollback=0, tup_inserted=101035, tup_updated=220767, tup_deleted=7, tup_fetched=327957, tup_returned=385493, blks_read=0, blks_hit=2607815, wal_records=1032068, wal_fpi=2, wal_bytes=123533016, wal_buffers_full=0, wal_write=78114, wal_sync=78114, wal_write_time=310.606, wal_sync_time=37687.066999999995}

PostgreSQL per second: commits 4049, WAL flushes 1905, WAL flush time 919 ms (max 961 ms), WAL write time 8 ms
