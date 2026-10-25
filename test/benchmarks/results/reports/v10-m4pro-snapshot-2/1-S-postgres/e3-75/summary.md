# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-091458-run-postgres-n1-saturation.

Open loop at 5324.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5088.7/s | 305319 | 14121 | 137.2 ms | 557.1 ms | 1417.2 ms | 1859.6 ms | 2313.6 ms | 2007.0 ms |

Errors: {http-429-wow.command.busy=14121}

Load generator: 1.05 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.20 | 1081 MB | 825 MB | 118 | 0.679 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.4% | 194.4% | 2.241GiB / 15.6GiB |
| wow-e2e-bench-postgres | 211.5% | 253.4% | 1.003GiB / 15.6GiB |

Store operations, postgres (seconds -0.06 to 60.945): {epoch=61.00509786605835, xact_commit=41627, xact_rollback=0, tup_inserted=923541, tup_updated=1057152, tup_deleted=0, tup_fetched=2206098, tup_returned=2281835, blks_read=888845, blks_hit=22816739, wal_records=6029356, wal_fpi=142, wal_bytes=1194351808, wal_buffers_full=0, wal_write=29155, wal_sync=29154, wal_write_time=1468.8060000000005, wal_sync_time=24399.957000000053}
Store operations, kafkaEndOffsets (seconds -30.006 to 62.227): {wow.transfer.account.state=1357402}

PostgreSQL per second: commits 682, WAL flushes 478, WAL flush time 400 ms (max 1203 ms), WAL write time 24 ms
