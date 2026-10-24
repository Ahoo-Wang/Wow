# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 30, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/async/2-F2/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 2.5 ms | 5.8 ms | 11.9 ms | 54.5 ms | 83.6 ms | 74.2 ms |

Load generator: 0.25 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.48 | 922 MB | 779 MB | 16 | 0.094 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 63.1% | 183.4% | 2.127GiB / 7.748GiB |
| wow-e2e-bench-postgres | 109.8% | 169.0% | 901.1MiB / 7.748GiB |

Store operations, postgres (seconds -0.04 to 60.961): {epoch=61.00035500526428, xact_commit=265823, xact_rollback=0, tup_inserted=144710, tup_updated=320626, tup_deleted=9, tup_fetched=404793, tup_returned=477344, blks_read=0, blks_hit=3733878, wal_records=1426531, wal_fpi=63, wal_bytes=207978057, wal_buffers_full=0, wal_write=169683, wal_sync=169683, wal_write_time=1451.8399999999992, wal_sync_time=44887.78599999999}
Store operations, kafkaEndOffsets (seconds -30.014 to 62.152): {wow.transfer.account.state=213736}

PostgreSQL per second: commits 4358, WAL flushes 2782, WAL flush time 736 ms (max 874 ms), WAL write time 24 ms
