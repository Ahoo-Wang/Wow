# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/sessions/6-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.6 ms | 2.7 ms | 4.9 ms | 11.9 ms | 31.7 ms | 22.3 ms |

Load generator: 0.42 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.50 | 1303 MB | 750 MB | 16 | 0.039 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.1% | 124.8% | 770.2MiB / 31.36GiB |
| wow-e2e-bench-postgres | 60.4% | 82.6% | 633.8MiB / 31.36GiB |

Store operations, postgres (seconds -0.05 to 40.952): {epoch=41.00274181365967, xact_commit=161865, xact_rollback=0, tup_inserted=101396, tup_updated=224395, tup_deleted=7, tup_fetched=336430, tup_returned=394782, blks_read=0, blks_hit=2642686, wal_records=1042090, wal_fpi=2, wal_bytes=124496212, wal_buffers_full=0, wal_write=75223, wal_sync=75223, wal_write_time=327.59200000000004, wal_sync_time=37443.810999999994}
Store operations, kafkaEndOffsets (seconds -19.888 to 41.797): {wow.transfer.account.state=147685, wow.transfer.commands=0}

PostgreSQL per second: commits 3948, WAL flushes 1835, WAL flush time 913 ms (max 939 ms), WAL write time 8 ms
