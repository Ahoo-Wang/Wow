# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/pool-32/2-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 2.6 ms | 4.7 ms | 15.3 ms | 34.6 ms | 21.5 ms |

Load generator: 0.39 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.12 | 1269 MB | 721 MB | 14 | 0.036 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 28.7% | 111.5% | 364.2MiB / 31.36GiB |
| wow-e2e-bench-postgres | 58.8% | 88.2% | 668.1MiB / 31.36GiB |

Store operations, postgres (seconds -0.058 to 40.943): {epoch=41.00104904174805, xact_commit=165092, xact_rollback=0, tup_inserted=101166, tup_updated=221293, tup_deleted=7, tup_fetched=320445, tup_returned=967302, blks_read=0, blks_hit=3284801, wal_records=1037835, wal_fpi=1, wal_bytes=123330470, wal_buffers_full=0, wal_write=77628, wal_sync=77628, wal_write_time=312.00499999999994, wal_sync_time=37475.842000000004}

PostgreSQL per second: commits 4027, WAL flushes 1893, WAL flush time 914 ms (max 944 ms), WAL write time 8 ms
