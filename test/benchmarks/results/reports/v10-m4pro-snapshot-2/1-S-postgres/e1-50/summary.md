# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-091458-run-postgres-n1-saturation.

Open loop at 10503.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10274.0/s | 616438 | 13742 | 28.0 ms | 195.6 ms | 516.1 ms | 1490.9 ms | 1646.3 ms | 1581.1 ms |

Errors: {exception-SocketException=183, http-429-wow.command.busy=13559}

Load generator: 2.34 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.28 | 1124 MB | 820 MB | 97 | 0.512 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.2% | 162.7% | 2.22GiB / 15.6GiB |
| wow-e2e-bench-postgres | 148.4% | 199.9% | 1.111GiB / 15.6GiB |

Store operations, postgres (seconds -0.074 to 60.926): {epoch=61.000016927719116, xact_commit=89086, xact_rollback=0, tup_inserted=616716, tup_updated=927155, tup_deleted=0, tup_fetched=1913267, tup_returned=1918871, blks_read=160343, blks_hit=16915126, wal_records=4535098, wal_fpi=61, wal_bytes=711572279, wal_buffers_full=0, wal_write=50408, wal_sync=50408, wal_write_time=1671.4850000000006, wal_sync_time=36700.24199999997}
Store operations, kafkaEndOffsets (seconds -29.991 to 62.262): {wow.transfer.account.state=923731}

PostgreSQL per second: commits 1460, WAL flushes 826, WAL flush time 602 ms (max 1797 ms), WAL write time 27 ms
