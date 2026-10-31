# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/5-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 2.5 ms | 4.6 ms | 14.7 ms | 27.2 ms | 19.5 ms |

Load generator: 0.41 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.40 | 5677 MB | 4802 MB | 2 | 0.010 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.4% | 111.4% | 1.114GiB / 31.36GiB |
| wow-e2e-bench-postgres | 59.5% | 80.7% | 628.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.034 to 40.968): {epoch=41.00188112258911, xact_commit=163914, xact_rollback=0, tup_inserted=100971, tup_updated=223732, tup_deleted=7, tup_fetched=334552, tup_returned=392702, blks_read=0, blks_hit=2632033, wal_records=1040463, wal_fpi=2, wal_bytes=124127791, wal_buffers_full=0, wal_write=75149, wal_sync=75149, wal_write_time=311.547, wal_sync_time=38530.104}
Store operations, kafkaEndOffsets (seconds -19.869 to 41.723): {wow.transfer.account.state=148041, wow.transfer.commands=0}

PostgreSQL per second: commits 3998, WAL flushes 1833, WAL flush time 940 ms (max 978 ms), WAL write time 8 ms
