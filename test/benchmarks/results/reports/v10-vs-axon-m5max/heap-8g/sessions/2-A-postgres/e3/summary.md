# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/2-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.4 ms | 5.3 ms | 8.5 ms | 24.7 ms | 37.6 ms | 32.8 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.73 | 5694 MB | 4883 MB | 2 | 0.021 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 29.6% | 100.6% | 497.9MiB / 31.36GiB |
| wow-e2e-bench-postgres | 77.4% | 88.7% | 317.9MiB / 31.36GiB |

Store operations, postgres (seconds -0.058 to 40.946): {epoch=41.00458383560181, xact_commit=197824, xact_rollback=0, tup_inserted=112187, tup_updated=57817, tup_deleted=6, tup_fetched=713317, tup_returned=1208593, blks_read=0, blks_hit=4334402, wal_records=605438, wal_fpi=0, wal_bytes=89053169, wal_buffers_full=0, wal_write=66903, wal_sync=66903, wal_write_time=324.03300000000013, wal_sync_time=32439.308000000005}

PostgreSQL per second: commits 4825, WAL flushes 1632, WAL flush time 791 ms (max 849 ms), WAL write time 8 ms
