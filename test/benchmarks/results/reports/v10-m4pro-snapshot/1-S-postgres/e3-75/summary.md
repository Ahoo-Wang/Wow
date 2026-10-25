# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-225058-run-postgres-n1-saturation.

Open loop at 3292.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 179.7/s | 10784 | 186736 | 3850.2 ms | 6815.7 ms | 9044.0 ms | 9895.9 ms | 9929.8 ms | 9929.8 ms |

Errors: {exception-BindException=113, exception-SocketException=312, generator-overload=997, http-429-wow.command.busy=133302, http-504-wow.wait.timeout=52012}

Load generator: 0.84 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.10 | 1265 MB | 950 MB | 73 | 0.825 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.1% | 149.4% | 2.155GiB / 15.6GiB |
| wow-e2e-bench-postgres | 109.5% | 192.0% | 987.8MiB / 15.6GiB |

Store operations, postgres (seconds -0.061 to 60.939): {epoch=61.000083923339844, xact_commit=109953, xact_rollback=0, tup_inserted=86438, tup_updated=32247, tup_deleted=0, tup_fetched=6323312, tup_returned=6330150, blks_read=5502123, blks_hit=3525496, wal_records=421972, wal_fpi=75, wal_bytes=111713413, wal_buffers_full=0, wal_write=3851, wal_sync=3850, wal_write_time=148.29599999999846, wal_sync_time=3018.473999999929}
Store operations, kafkaEndOffsets (seconds -30.023 to 70.017): {wow.transfer.account.state=45473}

PostgreSQL per second: commits 1802, WAL flushes 63, WAL flush time 49 ms (max 336 ms), WAL write time 2 ms
