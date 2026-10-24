# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-postgres-up.

Open loop at 1280.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1254.7/s | 75282 | 1518 | 129.5 ms | 1540.1 ms | 3850.2 ms | 4358.1 ms | 5654.5 ms | 4587.5 ms |

Errors: {http-429-wow.command.busy=1518}

Load generator: 0.46 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.49 | 1060 MB | 864 MB | 54 | 0.536 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 52.0% | 168.2% | 2.215GiB / 15.6GiB |
| wow-e2e-bench-postgres | 288.7% | 466.8% | 955.5MiB / 15.6GiB |

Store operations, postgres (seconds -0.05 to 60.946): {epoch=60.99574398994446, xact_commit=214049, xact_rollback=0, tup_inserted=227511, tup_updated=364726, tup_deleted=0, tup_fetched=7303852, tup_returned=7311386, blks_read=5874512, blks_hit=7835471, wal_records=1757510, wal_fpi=94447, wal_bytes=859504646, wal_buffers_full=0, wal_write=57369, wal_sync=57368, wal_write_time=3362.536, wal_sync_time=43386.70299999998}
Store operations, kafkaEndOffsets (seconds -30.01 to 62.311): {wow.transfer.account.state=346742}

PostgreSQL per second: commits 3509, WAL flushes 940, WAL flush time 711 ms (max 1888 ms), WAL write time 55 ms
