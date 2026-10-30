# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/5-K-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.3 ms | 4.3 ms | 7.1 ms | 23.0 ms | 42.3 ms | 37.9 ms |

Load generator: 0.13 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.22 | 1337 MB | 775 MB | 11 | 0.029 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.6% | 109.7% | 994.1MiB / 31.36GiB |
| wow-e2e-bench-postgres | 50.4% | 74.2% | 739.4MiB / 31.36GiB |

Store operations, postgres (seconds -0.052 to 40.946): {epoch=40.99810814857483, xact_commit=160160, xact_rollback=0, tup_inserted=84512, tup_updated=190622, tup_deleted=7, tup_fetched=244526, tup_returned=288963, blks_read=0, blks_hit=2269108, wal_records=854227, wal_fpi=0, wal_bytes=122790227, wal_buffers_full=0, wal_write=82106, wal_sync=82106, wal_write_time=291.67100000000005, wal_sync_time=37468.37200000002}
Store operations, kafkaEndOffsets (seconds -20.04 to 41.807): {wow.transfer.account.state=124859, wow.transfer.commands=0}

PostgreSQL per second: commits 3907, WAL flushes 2003, WAL flush time 914 ms (max 983 ms), WAL write time 7 ms
