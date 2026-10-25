# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 11, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-225058-run-postgres-n1-saturation.

Open loop at 2195.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 820.5/s | 49230 | 82470 | 2719.7 ms | 4177.9 ms | 6062.1 ms | 9175.0 ms | 9933.0 ms | 9568.3 ms |

Errors: {http-429-wow.command.busy=44759, http-504-wow.wait.timeout=37711}

Load generator: 0.55 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.81 | 1264 MB | 993 MB | 122 | 1.578 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.7% | 159.3% | 2.158GiB / 15.6GiB |
| wow-e2e-bench-postgres | 192.0% | 233.1% | 982.6MiB / 15.6GiB |

Store operations, postgres (seconds -0.057 to 60.943): {epoch=60.99994492530823, xact_commit=294705, xact_rollback=0, tup_inserted=179311, tup_updated=116113, tup_deleted=1, tup_fetched=10074384, tup_returned=10089928, blks_read=8551183, blks_hit=7239932, wal_records=988445, wal_fpi=463, wal_bytes=223904201, wal_buffers_full=0, wal_write=7237, wal_sync=7237, wal_write_time=286.3650000000016, wal_sync_time=5474.233000000007}
Store operations, kafkaEndOffsets (seconds -30.032 to 69.996): {wow.transfer.account.state=238130}

PostgreSQL per second: commits 4831, WAL flushes 119, WAL flush time 90 ms (max 417 ms), WAL write time 5 ms
