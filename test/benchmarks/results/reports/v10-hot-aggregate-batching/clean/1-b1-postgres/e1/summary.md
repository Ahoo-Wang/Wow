# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 22, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch-clean/1-b1-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 0.7 ms | 1.8 ms | 4.0 ms | 29.2 ms | 59.1 ms | 52.0 ms |

Load generator: 0.36 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.22 | 1015 MB | 776 MB | 16 | 0.081 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.5% | 173.2% | 1.728GiB / 7.748GiB |
| wow-e2e-bench-postgres | 59.3% | 130.2% | 654.3MiB / 7.748GiB |

Store operations, postgres (seconds -0.951 to 60.053): {epoch=61.00450897216797, xact_commit=234941, xact_rollback=0, tup_inserted=116269, tup_updated=264263, tup_deleted=7, tup_fetched=327302, tup_returned=403748, blks_read=0, blks_hit=2901776, wal_records=1169414, wal_fpi=2, wal_bytes=139894189, wal_buffers_full=0, wal_write=210608, wal_sync=210608, wal_write_time=1105.0910000000001, wal_sync_time=32310.04899999999}
Store operations, kafkaEndOffsets (seconds -29.777 to 62.09): {wow.transfer.account.state=169563}

PostgreSQL per second: commits 3851, WAL flushes 3452, WAL flush time 530 ms (max 794 ms), WAL write time 18 ms
