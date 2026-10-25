# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 18, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-225058-run-postgres-n1-saturation.

Open loop at 15903.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0.0/s | 0 | 954180 | 0.0 ms | 0.0 ms | 0.0 ms | 0.0 ms | 0.0 ms | - |

Errors: {exception-BindException=109293, exception-ConnectException=108820, exception-HttpTimeoutException=939, exception-IOException=11, exception-SocketException=1, generator-overload=732213, http-429-wow.command.busy=1561, http-504-wow.wait.timeout=1342}

Load generator: 2.83 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.06 | 784 MB | 851 MB | 1 | 0.043 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 33.4% | 131.3% | 2.277GiB / 15.6GiB |
| wow-e2e-bench-postgres | 4.0% | 13.8% | 999.1MiB / 15.6GiB |

Store operations, postgres (seconds -0.066 to 60.934): {epoch=60.999361991882324, xact_commit=229, xact_rollback=0, tup_inserted=0, tup_updated=29, tup_deleted=0, tup_fetched=1034, tup_returned=3045, blks_read=79384, blks_hit=109801, wal_records=91320, wal_fpi=3, wal_bytes=5405294, wal_buffers_full=0, wal_write=91, wal_sync=91, wal_write_time=15.345000000001164, wal_sync_time=63.38600000005681}
Store operations, kafkaEndOffsets (seconds -30.031 to 79.049): {wow.transfer.account.state=317}

PostgreSQL per second: commits 4, WAL flushes 1, WAL flush time 1 ms (max 6 ms), WAL write time 0 ms
