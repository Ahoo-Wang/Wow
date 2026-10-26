# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-220353-run-postgres-n1-saturation.

Open loop at 5225.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5225.0/s | 313500 | 0 | 114.2 ms | 230.4 ms | 409.6 ms | 589.8 ms | 900.1 ms | 647.2 ms |

Load generator: 1.30 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.79 | 1175 MB | 800 MB | 111 | 0.469 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.6% | 173.4% | 2.292GiB / 15.6GiB |
| wow-e2e-bench-postgres | 225.5% | 246.9% | 877.9MiB / 15.6GiB |

Store operations, postgres (seconds -0.071 to 60.933): {epoch=61.0045051574707, xact_commit=50528, xact_rollback=0, tup_inserted=944055, tup_updated=1109261, tup_deleted=0, tup_fetched=2311740, tup_returned=2337029, blks_read=930509, blks_hit=23565549, wal_records=6319382, wal_fpi=100, wal_bytes=1234518695, wal_buffers_full=0, wal_write=36613, wal_sync=36612, wal_write_time=995.4510000000009, wal_sync_time=23212.703000000096}
Store operations, kafkaEndOffsets (seconds -30.046 to 62.152): {wow.transfer.account.state=1384459}

PostgreSQL per second: commits 828, WAL flushes 600, WAL flush time 381 ms (max 725 ms), WAL write time 16 ms
