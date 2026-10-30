# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-045921-run-postgres-n1-saturation.

Open loop at 6825.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5372.4/s | 322343 | 87157 | 99.3 ms | 716.8 ms | 798.7 ms | 872.4 ms | 995.3 ms | 905.2 ms |

Errors: {generator-overload=87157}

Load generator: 1.17 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.78 | 1369 MB | 834 MB | 209 | 0.582 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.6% | 134.1% | 1.218GiB / 31.36GiB |
| wow-e2e-bench-postgres | 154.4% | 251.1% | 674.7MiB / 31.36GiB |

Store operations, postgres (seconds -0.058 to 60.943): {epoch=61.00064992904663, xact_commit=36815, xact_rollback=0, tup_inserted=971367, tup_updated=1113394, tup_deleted=12, tup_fetched=2625760, tup_returned=2639418, blks_read=1235226, blks_hit=23841301, wal_records=6294548, wal_fpi=1846, wal_bytes=1221514040, wal_buffers_full=0, wal_write=21080, wal_sync=21080, wal_write_time=430.7440000000006, wal_sync_time=45198.956000000006}
Store operations, kafkaEndOffsets (seconds -30.037 to 62.001): {wow.transfer.account.state=1447708, wow.transfer.commands=0}

PostgreSQL per second: commits 603, WAL flushes 346, WAL flush time 741 ms (max 1536 ms), WAL write time 7 ms
