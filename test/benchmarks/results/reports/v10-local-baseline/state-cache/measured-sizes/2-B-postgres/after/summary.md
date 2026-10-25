# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 27, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/2-B-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.6 ms | 29.2 ms | 207.9 ms | 466.9 ms | 568.4 ms | 518.1 ms |

Load generator: 0.54 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.50 | 878 MB | 825 MB | 17 | 0.177 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 69.9% | 177.3% | 2.045GiB / 7.748GiB |
| wow-e2e-bench-postgres | 107.5% | 213.5% | 730.6MiB / 7.748GiB |

Store operations, postgres (seconds -0.935 to 60.066): {epoch=61.00075817108154, xact_commit=204032, xact_rollback=0, tup_inserted=116002, tup_updated=260228, tup_deleted=0, tup_fetched=336585, tup_returned=382884, blks_read=19, blks_hit=2826688, wal_records=1123608, wal_fpi=375, wal_bytes=139835711, wal_buffers_full=0, wal_write=146320, wal_sync=146320, wal_write_time=1516.098, wal_sync_time=44375.397}
Store operations, kafkaEndOffsets (seconds -29.985 to 62.188): {wow.transfer.account.state=169725}

PostgreSQL per second: commits 3345, WAL flushes 2399, WAL flush time 727 ms (max 961 ms), WAL write time 25 ms
