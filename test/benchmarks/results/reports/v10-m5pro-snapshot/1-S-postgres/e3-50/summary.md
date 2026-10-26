# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-220353-run-postgres-n1-saturation.

Open loop at 3483.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3328.7/s | 199720 | 9260 | 49.9 ms | 501.8 ms | 2162.7 ms | 2523.1 ms | 2709.0 ms | 2572.3 ms |

Errors: {http-429-wow.command.busy=9260}

Load generator: 0.97 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.19 | 1292 MB | 805 MB | 78 | 0.280 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.0% | 184.7% | 2.17GiB / 15.6GiB |
| wow-e2e-bench-postgres | 154.4% | 232.0% | 903.8MiB / 15.6GiB |

Store operations, postgres (seconds -0.06 to 60.94): {epoch=61.00039982795715, xact_commit=85731, xact_rollback=0, tup_inserted=600933, tup_updated=873957, tup_deleted=0, tup_fetched=1838656, tup_returned=1844420, blks_read=548903, blks_hit=16527860, wal_records=4393060, wal_fpi=83, wal_bytes=800699248, wal_buffers_full=0, wal_write=56882, wal_sync=56882, wal_write_time=825.1409999999996, wal_sync_time=33403.90500000003}
Store operations, kafkaEndOffsets (seconds -30.035 to 62.235): {wow.transfer.account.state=885097}

PostgreSQL per second: commits 1405, WAL flushes 932, WAL flush time 548 ms (max 2460 ms), WAL write time 14 ms
