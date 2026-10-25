# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-225058-run-postgres-n1-saturation.

Open loop at 1097.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 755.7/s | 45341 | 20479 | 606.2 ms | 7438.3 ms | 9437.2 ms | 9961.5 ms | 10000.7 ms | 9895.9 ms |

Errors: {exception-ConnectException=151, http-504-wow.wait.timeout=20328}

Load generator: 0.34 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.76 | 1314 MB | 876 MB | 79 | 0.373 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.1% | 134.8% | 2.158GiB / 15.6GiB |
| wow-e2e-bench-postgres | 171.4% | 241.3% | 977.3MiB / 15.6GiB |

Store operations, postgres (seconds -0.051 to 60.949): {epoch=60.99996590614319, xact_commit=433460, xact_rollback=0, tup_inserted=166305, tup_updated=223700, tup_deleted=11, tup_fetched=10649279, tup_returned=10705979, blks_read=7854736, blks_hit=9061217, wal_records=1179615, wal_fpi=22821, wal_bytes=287948538, wal_buffers_full=0, wal_write=53329, wal_sync=53329, wal_write_time=463.1670000000013, wal_sync_time=15616.253999999957}
Store operations, kafkaEndOffsets (seconds -30.024 to 69.995): {wow.transfer.account.state=229308}

PostgreSQL per second: commits 7106, WAL flushes 874, WAL flush time 256 ms (max 684 ms), WAL write time 8 ms
