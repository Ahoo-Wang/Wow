# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-postgres-up.

Open loop at 427.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 427.0/s | 25620 | 0 | 2.8 ms | 231.4 ms | 1343.5 ms | 1884.2 ms | 1982.8 ms | 1941.5 ms |

Load generator: 0.12 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.74 | 950 MB | 844 MB | 15 | 0.088 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 33.5% | 110.3% | 2.259GiB / 15.6GiB |
| wow-e2e-bench-postgres | 68.5% | 187.8% | 877.2MiB / 15.6GiB |

Store operations, postgres (seconds -0.956 to 60.043): {epoch=60.99969720840454, xact_commit=174157, xact_rollback=0, tup_inserted=77720, tup_updated=182870, tup_deleted=12, tup_fetched=2401923, tup_returned=2409859, blks_read=1825407, blks_hit=2995052, wal_records=780634, wal_fpi=7029, wal_bytes=153809293, wal_buffers_full=0, wal_write=125633, wal_sync=125633, wal_write_time=794.5279999999984, wal_sync_time=29372.565999999875}
Store operations, kafkaEndOffsets (seconds -30.005 to 62.101): {wow.transfer.account.state=129586}

PostgreSQL per second: commits 2855, WAL flushes 2060, WAL flush time 482 ms (max 1955 ms), WAL write time 13 ms
