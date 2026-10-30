# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/6-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.6 ms | 2.6 ms | 4.6 ms | 19.5 ms | 34.9 ms | 26.5 ms |

Load generator: 0.37 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.39 | 1290 MB | 737 MB | 28 | 0.046 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.7% | 106.1% | 906.9MiB / 31.36GiB |
| wow-e2e-bench-postgres | 59.0% | 80.3% | 630.2MiB / 31.36GiB |

Store operations, postgres (seconds -0.04 to 40.959): {epoch=40.99878191947937, xact_commit=163769, xact_rollback=0, tup_inserted=100895, tup_updated=223384, tup_deleted=7, tup_fetched=333458, tup_returned=391659, blks_read=0, blks_hit=2627187, wal_records=1038738, wal_fpi=2, wal_bytes=123762728, wal_buffers_full=0, wal_write=75056, wal_sync=75056, wal_write_time=296.608, wal_sync_time=38518.689000000006}
Store operations, kafkaEndOffsets (seconds -19.874 to 41.813): {wow.transfer.account.state=147893, wow.transfer.commands=0}

PostgreSQL per second: commits 3994, WAL flushes 1830, WAL flush time 939 ms (max 988 ms), WAL write time 7 ms
