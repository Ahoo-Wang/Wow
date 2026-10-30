# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/2-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.5 ms | 5.7 ms | 12.7 ms | 28.8 ms | 33.7 ms | 27.3 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.83 | 1573 MB | 952 MB | 20 | 0.289 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 29.6% | 98.7% | 453.6MiB / 31.36GiB |
| wow-e2e-bench-postgres | 78.5% | 85.3% | 318.5MiB / 31.36GiB |

Store operations, postgres (seconds -0.056 to 40.946): {epoch=41.00173211097717, xact_commit=195648, xact_rollback=0, tup_inserted=112346, tup_updated=58177, tup_deleted=6, tup_fetched=755549, tup_returned=1327702, blks_read=0, blks_hit=4389462, wal_records=607224, wal_fpi=1, wal_bytes=89831531, wal_buffers_full=0, wal_write=66371, wal_sync=66371, wal_write_time=324.13800000000003, wal_sync_time=32096.335000000006}

PostgreSQL per second: commits 4772, WAL flushes 1619, WAL flush time 783 ms (max 840 ms), WAL write time 8 ms
