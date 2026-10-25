# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon/5-K-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.1 ms | 2.7 ms | 27.6 ms | 106.0 ms | 146.4 ms | 129.0 ms |

Load generator: 0.14 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.88 | 912 MB | 674 MB | 10 | 0.050 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.3% | 157.1% | 1.769GiB / 7.748GiB |
| wow-e2e-bench-postgres | 49.2% | 91.1% | 736.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.053 to 40.953): {epoch=41.005704164505005, xact_commit=165134, xact_rollback=0, tup_inserted=84163, tup_updated=190050, tup_deleted=7, tup_fetched=235727, tup_returned=284094, blks_read=0, blks_hit=2248769, wal_records=851349, wal_fpi=0, wal_bytes=124841564, wal_buffers_full=0, wal_write=156898, wal_sync=156898, wal_write_time=548.5139999999999, wal_sync_time=17825.952999999994}
Store operations, kafkaEndOffsets (seconds -20.031 to 41.778): {wow.transfer.account.state=124299}

PostgreSQL per second: commits 4028, WAL flushes 3827, WAL flush time 435 ms (max 582 ms), WAL write time 13 ms
