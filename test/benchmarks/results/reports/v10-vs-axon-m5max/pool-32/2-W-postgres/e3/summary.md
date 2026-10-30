# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/pool-32/2-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.2 ms | 4.4 ms | 6.7 ms | 22.7 ms | 32.4 ms | 24.4 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.04 | 1492 MB | 966 MB | 52 | 0.071 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 25.2% | 103.2% | 363.4MiB / 31.36GiB |
| wow-e2e-bench-postgres | 49.6% | 75.9% | 795.2MiB / 31.36GiB |

Store operations, postgres (seconds -0.059 to 40.943): {epoch=41.00189185142517, xact_commit=160726, xact_rollback=0, tup_inserted=84751, tup_updated=191199, tup_deleted=6, tup_fetched=246967, tup_returned=289690, blks_read=0, blks_hit=2280946, wal_records=877700, wal_fpi=0, wal_bytes=124755061, wal_buffers_full=0, wal_write=83026, wal_sync=83026, wal_write_time=280.81499999999994, wal_sync_time=37086.693000000014}

PostgreSQL per second: commits 3920, WAL flushes 2025, WAL flush time 905 ms (max 965 ms), WAL write time 7 ms
