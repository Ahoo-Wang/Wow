# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/5-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.9 ms | 12.4 ms | 92.2 ms | 116.7 ms | 142.4 ms | 131.1 ms |

Load generator: 0.37 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.37 | 1320 MB | 763 MB | 16 | 0.039 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.6% | 114.4% | 796.2MiB / 31.36GiB |
| wow-e2e-bench-postgres | 50.2% | 68.7% | 633MiB / 31.36GiB |

Store operations, postgres (seconds -0.053 to 40.952): {epoch=41.0052649974823, xact_commit=122826, xact_rollback=0, tup_inserted=100786, tup_updated=219635, tup_deleted=6, tup_fetched=348414, tup_returned=406255, blks_read=0, blks_hit=2647419, wal_records=1011590, wal_fpi=2, wal_bytes=123156353, wal_buffers_full=0, wal_write=53027, wal_sync=53027, wal_write_time=201.716, wal_sync_time=38926.391}
Store operations, kafkaEndOffsets (seconds -19.894 to 41.814): {wow.transfer.account.state=148010, wow.transfer.commands=0}

PostgreSQL per second: commits 2996, WAL flushes 1293, WAL flush time 949 ms (max 1355 ms), WAL write time 5 ms
