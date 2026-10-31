# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/6-K-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.2 ms | 4.3 ms | 7.5 ms | 49.4 ms | 86.5 ms | 77.8 ms |

Load generator: 0.13 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.23 | 5689 MB | 4909 MB | 1 | 0.004 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.2% | 111.8% | 923.4MiB / 31.36GiB |
| wow-e2e-bench-postgres | 51.3% | 75.7% | 743MiB / 31.36GiB |

Store operations, postgres (seconds -0.06 to 40.942): {epoch=41.001612186431885, xact_commit=160268, xact_rollback=0, tup_inserted=84919, tup_updated=191391, tup_deleted=7, tup_fetched=246284, tup_returned=290869, blks_read=0, blks_hit=2280528, wal_records=857568, wal_fpi=0, wal_bytes=123370869, wal_buffers_full=0, wal_write=82182, wal_sync=82182, wal_write_time=305.879, wal_sync_time=37695.12699999999}
Store operations, kafkaEndOffsets (seconds -20.041 to 41.77): {wow.transfer.account.state=124738, wow.transfer.commands=0}

PostgreSQL per second: commits 3909, WAL flushes 2004, WAL flush time 919 ms (max 959 ms), WAL write time 7 ms
