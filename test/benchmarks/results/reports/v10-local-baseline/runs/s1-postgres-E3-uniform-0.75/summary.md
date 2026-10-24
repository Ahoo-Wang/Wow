# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 41, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-postgres-up.

Open loop at 1245.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 266.0/s | 15959 | 58740 | 5799.9 ms | 9437.2 ms | 9895.9 ms | 10027.0 ms | 10058.6 ms | 10000.7 ms |

Errors: {http-429-wow.command.busy=46814, http-504-wow.wait.timeout=11926}

Load generator: 0.50 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.25 | 1310 MB | 922 MB | 95 | 2.696 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 70.3% | 173.4% | 2.175GiB / 7.748GiB |
| wow-e2e-bench-postgres | 179.9% | 250.5% | 780.1MiB / 7.748GiB |

Store operations, postgres (seconds -0.298 to 60.718): {epoch=61.01606106758118, xact_commit=390083, xact_rollback=0, tup_inserted=97347, tup_updated=63396, tup_deleted=0, tup_fetched=2153453, tup_returned=2190101, blks_read=1077262, blks_hit=4346937, wal_records=601878, wal_fpi=132, wal_bytes=114729367, wal_buffers_full=0, wal_write=3691, wal_sync=3691, wal_write_time=247.362000000001, wal_sync_time=7369.550000000047}
Store operations, kafkaEndOffsets (seconds -30.259 to 69.71): {wow.transfer.account.state=198474}

PostgreSQL per second: commits 6393, WAL flushes 60, WAL flush time 121 ms (max 590 ms), WAL write time 4 ms
