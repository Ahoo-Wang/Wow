# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-045921-run-postgres-n1-saturation.

Open loop at 4550.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4214.6/s | 252873 | 20127 | 56.6 ms | 585.7 ms | 708.6 ms | 897.0 ms | 959.1 ms | 929.8 ms |

Errors: {generator-overload=20127}

Load generator: 0.86 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.33 | 1367 MB | 844 MB | 110 | 0.308 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.0% | 125.5% | 1.131GiB / 31.36GiB |
| wow-e2e-bench-postgres | 136.2% | 178.2% | 675MiB / 31.36GiB |

Store operations, postgres (seconds -0.052 to 60.953): {epoch=61.00497508049011, xact_commit=71329, xact_rollback=0, tup_inserted=767069, tup_updated=1026634, tup_deleted=0, tup_fetched=2197114, tup_returned=2208248, blks_read=882731, blks_hit=20184882, wal_records=5303083, wal_fpi=112194, wal_bytes=1337529774, wal_buffers_full=0, wal_write=38596, wal_sync=38596, wal_write_time=581.3989999999994, wal_sync_time=47133.498999999836}
Store operations, kafkaEndOffsets (seconds -30.031 to 61.971): {wow.transfer.account.state=1131632, wow.transfer.commands=0}

PostgreSQL per second: commits 1169, WAL flushes 633, WAL flush time 773 ms (max 1317 ms), WAL write time 10 ms
