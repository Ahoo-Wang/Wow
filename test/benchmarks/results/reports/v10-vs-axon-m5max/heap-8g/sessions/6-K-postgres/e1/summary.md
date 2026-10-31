# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/6-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.6 ms | 6.3 ms | 53.8 ms | 107.5 ms | 124.4 ms | 115.2 ms |

Load generator: 0.41 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.47 | 5686 MB | 4896 MB | 2 | 0.011 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.0% | 113.3% | 766.9MiB / 31.36GiB |
| wow-e2e-bench-postgres | 55.7% | 83.4% | 633.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.053 to 40.948): {epoch=41.00144386291504, xact_commit=148481, xact_rollback=0, tup_inserted=101424, tup_updated=222442, tup_deleted=7, tup_fetched=341510, tup_returned=399554, blks_read=0, blks_hit=2648031, wal_records=1032492, wal_fpi=2, wal_bytes=124261818, wal_buffers_full=0, wal_write=66842, wal_sync=66842, wal_write_time=269.026, wal_sync_time=38919.914000000004}
Store operations, kafkaEndOffsets (seconds -19.894 to 41.757): {wow.transfer.account.state=148050, wow.transfer.commands=0}

PostgreSQL per second: commits 3621, WAL flushes 1630, WAL flush time 949 ms (max 1084 ms), WAL write time 7 ms
