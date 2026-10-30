# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/4-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.1 ms | 4.5 ms | 7.4 ms | 15.7 ms | 27.5 ms | 19.2 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.08 | 1487 MB | 955 MB | 50 | 0.068 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 25.6% | 105.0% | 373.6MiB / 31.36GiB |
| wow-e2e-bench-postgres | 49.9% | 75.3% | 728.5MiB / 31.36GiB |

Store operations, postgres (seconds -0.065 to 40.936): {epoch=41.00137495994568, xact_commit=161000, xact_rollback=0, tup_inserted=84672, tup_updated=191699, tup_deleted=6, tup_fetched=245889, tup_returned=289971, blks_read=0, blks_hit=2277498, wal_records=857705, wal_fpi=0, wal_bytes=123120626, wal_buffers_full=0, wal_write=83986, wal_sync=83986, wal_write_time=285.711, wal_sync_time=37151.64600000001}

PostgreSQL per second: commits 3927, WAL flushes 2048, WAL flush time 906 ms (max 963 ms), WAL write time 7 ms
