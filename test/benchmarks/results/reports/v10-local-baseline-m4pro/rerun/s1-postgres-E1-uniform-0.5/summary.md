# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-postgres-up.

Open loop at 5616.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5589.0/s | 335342 | 1618 | 28.8 ms | 219.1 ms | 598.0 ms | 1073.2 ms | 1256.1 ms | 1138.7 ms |

Errors: {http-429-wow.command.busy=1618}

Load generator: 1.51 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.34 | 1051 MB | 822 MB | 64 | 0.326 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 44.7% | 149.2% | 2.077GiB / 15.6GiB |
| wow-e2e-bench-postgres | 206.6% | 289.1% | 1.019GiB / 15.6GiB |

Store operations, postgres (seconds -0.057 to 60.944): {epoch=61.00149917602539, xact_commit=339325, xact_rollback=0, tup_inserted=337730, tup_updated=555974, tup_deleted=9, tup_fetched=5452893, tup_returned=5503018, blks_read=3458998, blks_hit=10295254, wal_records=2678971, wal_fpi=24703, wal_bytes=532540490, wal_buffers_full=0, wal_write=94370, wal_sync=94370, wal_write_time=1046.1369999999988, wal_sync_time=44242.496999999974}
Store operations, kafkaEndOffsets (seconds -30.03 to 62.091): {wow.transfer.account.state=494975}

PostgreSQL per second: commits 5562, WAL flushes 1547, WAL flush time 725 ms (max 1032 ms), WAL write time 17 ms
