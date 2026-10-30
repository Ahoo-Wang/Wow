# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-045921-run-postgres-n1-saturation.

Open loop at 24885.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 24235.2/s | 1454112 | 38987 | 52.5 ms | 86.0 ms | 126.0 ms | 532.5 ms | 658.4 ms | 581.6 ms |

Errors: {generator-overload=38987}

Load generator: 2.99 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 4.85 | 1395 MB | 845 MB | 254 | 0.846 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.6% | 150.6% | 1.126GiB / 31.36GiB |
| wow-e2e-bench-postgres | 223.3% | 241.3% | 622.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.054 to 60.946): {epoch=60.99933385848999, xact_commit=36361, xact_rollback=0, tup_inserted=1459030, tup_updated=1608074, tup_deleted=0, tup_fetched=3345334, tup_returned=3350827, blks_read=912021, blks_hit=35755086, wal_records=9292106, wal_fpi=1251, wal_bytes=1580601630, wal_buffers_full=0, wal_write=20332, wal_sync=20332, wal_write_time=651.1620000000003, wal_sync_time=38409.50399999996}
Store operations, kafkaEndOffsets (seconds -30.044 to 62.019): {wow.transfer.account.state=2147268, wow.transfer.commands=0}

PostgreSQL per second: commits 596, WAL flushes 333, WAL flush time 630 ms (max 1203 ms), WAL write time 11 ms
