# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-091458-run-postgres-n1-saturation.

Open loop at 3549.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3420.4/s | 205223 | 7717 | 84.5 ms | 872.4 ms | 1728.5 ms | 2113.5 ms | 2395.9 ms | 2195.5 ms |

Errors: {http-429-wow.command.busy=7717}

Load generator: 0.67 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.66 | 1078 MB | 858 MB | 77 | 0.417 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.5% | 147.0% | 2.221GiB / 15.6GiB |
| wow-e2e-bench-postgres | 153.6% | 219.4% | 1.008GiB / 15.6GiB |

Store operations, postgres (seconds -0.058 to 60.944): {epoch=61.00174808502197, xact_commit=62475, xact_rollback=0, tup_inserted=615725, tup_updated=828728, tup_deleted=0, tup_fetched=1735120, tup_returned=1740647, blks_read=534816, blks_hit=16281170, wal_records=4296923, wal_fpi=831, wal_bytes=809771167, wal_buffers_full=0, wal_write=40739, wal_sync=40739, wal_write_time=1856.1780000000035, wal_sync_time=27984.62699999998}
Store operations, kafkaEndOffsets (seconds -30.012 to 62.176): {wow.transfer.account.state=925126}

PostgreSQL per second: commits 1024, WAL flushes 668, WAL flush time 459 ms (max 1894 ms), WAL write time 30 ms
