# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 13, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/e3/runs/postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 1.0 ms | 1.5 ms | 1.9 ms | 5.7 ms | 22.4 ms | 13.7 ms |

Load generator: 0.12 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.83 | 1046 MB | 726 MB | 25 | 0.068 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.2% | 124.7% | 1.917GiB / 7.748GiB |
| wow-e2e-bench-postgres | 35.5% | 96.1% | 1.924GiB / 7.748GiB |

Store operations, postgres (seconds -0.052 to 60.954): {epoch=61.00602698326111, xact_commit=254832, xact_rollback=0, tup_inserted=127256, tup_updated=287314, tup_deleted=7, tup_fetched=357062, tup_returned=422552, blks_read=0, blks_hit=3356844, wal_records=1284616, wal_fpi=0, wal_bytes=186720928, wal_buffers_full=0, wal_write=254101, wal_sync=254101, wal_write_time=411.39599999999996, wal_sync_time=73.41599999999997}
Store operations, kafkaEndOffsets (seconds -30.03 to 62.006): {wow.transfer.account.state=187548}

PostgreSQL per second: commits 4177, WAL flushes 4165, WAL flush time 1 ms (max 1 ms), WAL write time 7 ms
