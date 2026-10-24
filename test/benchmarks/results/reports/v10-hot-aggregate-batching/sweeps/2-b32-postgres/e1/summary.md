# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 33, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/2-b32-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.9 ms | 2.5 ms | 7.9 ms | 56.8 ms | 104.9 ms | 75.8 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.29 | 1068 MB | 849 MB | 20 | 0.151 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.9% | 188.6% | 2.003GiB / 7.748GiB |
| wow-e2e-bench-postgres | 74.3% | 111.0% | 1.185GiB / 7.748GiB |

Store operations, postgres (seconds -0.963 to 60.041): {epoch=61.0041139125824, xact_commit=240657, xact_rollback=0, tup_inserted=116283, tup_updated=264470, tup_deleted=0, tup_fetched=400772, tup_returned=460778, blks_read=15355, blks_hit=2980417, wal_records=1164475, wal_fpi=9138, wal_bytes=196422047, wal_buffers_full=0, wal_write=195568, wal_sync=195568, wal_write_time=1362.286, wal_sync_time=35746.533999999985}
Store operations, kafkaEndOffsets (seconds -29.744 to 62.003): {wow.transfer.account.state=197075}

PostgreSQL per second: commits 3945, WAL flushes 3206, WAL flush time 586 ms (max 791 ms), WAL write time 22 ms
