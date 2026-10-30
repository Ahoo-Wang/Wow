# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/1-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 2.5 ms | 4.5 ms | 15.0 ms | 27.2 ms | 19.5 ms |

Load generator: 0.39 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.20 | 1501 MB | 964 MB | 35 | 0.061 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 29.0% | 105.8% | 358.6MiB / 31.36GiB |
| wow-e2e-bench-postgres | 58.2% | 84.3% | 631.3MiB / 31.36GiB |

Store operations, postgres (seconds -0.06 to 40.942): {epoch=41.00125312805176, xact_commit=166004, xact_rollback=0, tup_inserted=101057, tup_updated=223038, tup_deleted=6, tup_fetched=330911, tup_returned=388760, blks_read=0, blks_hit=2620941, wal_records=1037847, wal_fpi=2, wal_bytes=123876355, wal_buffers_full=0, wal_write=76648, wal_sync=76648, wal_write_time=290.764, wal_sync_time=38581.729}

PostgreSQL per second: commits 4049, WAL flushes 1869, WAL flush time 941 ms (max 973 ms), WAL write time 7 ms
