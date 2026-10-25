# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-102320-run-postgres-n1-saturation.

Open loop at 8949.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8269.1/s | 496144 | 40796 | 12.4 ms | 565.2 ms | 1294.3 ms | 3457.0 ms | 3580.2 ms | 3538.9 ms |

Errors: {http-429-wow.command.busy=40796}

Load generator: 2.08 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.32 | 1706 MB | 1612 MB | 33 | 0.248 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.0% | 147.9% | 2.067GiB / 15.6GiB |
| wow-e2e-bench-postgres | 113.5% | 166.4% | 935.4MiB / 15.6GiB |

Store operations, postgres (seconds -0.959 to 60.041): {epoch=61.00019598007202, xact_commit=140935, xact_rollback=0, tup_inserted=499238, tup_updated=858867, tup_deleted=9, tup_fetched=1594361, tup_returned=1694199, blks_read=22667, blks_hit=11986212, wal_records=4082660, wal_fpi=1, wal_bytes=573439480, wal_buffers_full=0, wal_write=89217, wal_sync=89217, wal_write_time=715.8469999999998, wal_sync_time=33086.15700000001}
Store operations, kafkaEndOffsets (seconds -29.664 to 62.164): {wow.transfer.account.state=751254}

PostgreSQL per second: commits 2310, WAL flushes 1463, WAL flush time 542 ms (max 1597 ms), WAL write time 12 ms
