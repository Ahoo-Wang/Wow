# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/3-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.5 ms | 5.8 ms | 17.7 ms | 33.5 ms | 40.0 ms | 31.6 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.88 | 1484 MB | 833 MB | 19 | 0.397 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 27.7% | 92.9% | 372.7MiB / 31.36GiB |
| wow-e2e-bench-postgres | 77.9% | 90.6% | 319.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.06 to 40.946): {epoch=41.00619983673096, xact_commit=195248, xact_rollback=0, tup_inserted=111999, tup_updated=57917, tup_deleted=6, tup_fetched=751195, tup_returned=1354000, blks_read=1, blks_hit=4375023, wal_records=605233, wal_fpi=1, wal_bytes=89746146, wal_buffers_full=0, wal_write=66463, wal_sync=66463, wal_write_time=318.95400000000006, wal_sync_time=31844.684999999998}

PostgreSQL per second: commits 4762, WAL flushes 1621, WAL flush time 777 ms (max 855 ms), WAL write time 8 ms
