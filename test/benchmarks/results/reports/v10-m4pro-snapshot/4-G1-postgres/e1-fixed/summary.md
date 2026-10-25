# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-235336-run-postgres-n1-saturation.

Open loop at 8064.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7994.9/s | 479692 | 4148 | 17.8 ms | 96.8 ms | 593.9 ms | 917.5 ms | 1113.4 ms | 1019.9 ms |

Errors: {http-429-wow.command.busy=4148}

Load generator: 2.00 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.76 | 1150 MB | 810 MB | 72 | 0.347 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.5% | 166.3% | 2.177GiB / 15.6GiB |
| wow-e2e-bench-postgres | 179.2% | 276.1% | 1.118GiB / 15.6GiB |

Store operations, postgres (seconds -0.999 to 60.002): {epoch=61.00094199180603, xact_commit=323443, xact_rollback=0, tup_inserted=486268, tup_updated=817249, tup_deleted=9, tup_fetched=1200323, tup_returned=1319563, blks_read=22374, blks_hit=10954074, wal_records=3795068, wal_fpi=1, wal_bytes=537881485, wal_buffers_full=0, wal_write=125817, wal_sync=125817, wal_write_time=985.3559999999998, wal_sync_time=41863.658000000025}
Store operations, kafkaEndOffsets (seconds -29.67 to 62.109): {wow.transfer.account.state=703564}

PostgreSQL per second: commits 5302, WAL flushes 2063, WAL flush time 686 ms (max 908 ms), WAL write time 16 ms
