# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/5-K-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.2 ms | 4.2 ms | 7.8 ms | 28.9 ms | 57.3 ms | 53.2 ms |

Load generator: 0.13 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.14 | 5680 MB | 4822 MB | 1 | 0.005 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 32.4% | 107.6% | 1008MiB / 31.36GiB |
| wow-e2e-bench-postgres | 51.2% | 76.3% | 730MiB / 31.36GiB |

Store operations, postgres (seconds -0.016 to 40.988): {epoch=41.00391483306885, xact_commit=160822, xact_rollback=0, tup_inserted=84828, tup_updated=190878, tup_deleted=7, tup_fetched=245845, tup_returned=289831, blks_read=0, blks_hit=2277521, wal_records=857289, wal_fpi=0, wal_bytes=123389018, wal_buffers_full=0, wal_write=82686, wal_sync=82686, wal_write_time=292.9799999999999, wal_sync_time=37665.319}
Store operations, kafkaEndOffsets (seconds -20.04 to 41.756): {wow.transfer.account.state=124794, wow.transfer.commands=0}

PostgreSQL per second: commits 3922, WAL flushes 2017, WAL flush time 919 ms (max 956 ms), WAL write time 7 ms
