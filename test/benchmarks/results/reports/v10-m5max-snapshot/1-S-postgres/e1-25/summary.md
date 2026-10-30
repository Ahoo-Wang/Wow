# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 9, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-045921-run-postgres-n1-saturation.

Open loop at 8295.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8295.0/s | 497700 | 0 | 4.6 ms | 45.8 ms | 139.3 ms | 193.5 ms | 285.1 ms | 207.9 ms |

Load generator: 1.90 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.97 | 1392 MB | 836 MB | 166 | 0.364 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.5% | 150.1% | 1.133GiB / 31.36GiB |
| wow-e2e-bench-postgres | 128.5% | 334.9% | 757.8MiB / 31.36GiB |

Store operations, postgres (seconds -0.958 to 60.04): {epoch=60.998069047927856, xact_commit=187525, xact_rollback=0, tup_inserted=502522, tup_updated=961304, tup_deleted=11, tup_fetched=3777148, tup_returned=11277100, blks_read=1624364, blks_hit=23766994, wal_records=4426867, wal_fpi=1571, wal_bytes=617000129, wal_buffers_full=0, wal_write=66660, wal_sync=66660, wal_write_time=593.7340000000004, wal_sync_time=53418.44499999995}
Store operations, kafkaEndOffsets (seconds -29.805 to 61.952): {wow.transfer.account.state=822829, wow.transfer.commands=0}

PostgreSQL per second: commits 3074, WAL flushes 1093, WAL flush time 876 ms (max 1165 ms), WAL write time 10 ms
