# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-230645-run-postgres-n1-saturation.

Open loop at 9128.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9128.0/s | 547680 | 0 | 5.4 ms | 14.5 ms | 40.7 ms | 163.8 ms | 235.3 ms | 206.8 ms |

Load generator: 2.45 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.43 | 1381 MB | 869 MB | 72 | 0.263 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 55.1% | 185.9% | 2.053GiB / 15.6GiB |
| wow-e2e-bench-postgres | 153.4% | 201.4% | 978.4MiB / 15.6GiB |

Store operations, postgres (seconds -0.085 to 60.947): {epoch=61.03191900253296, xact_commit=167978, xact_rollback=0, tup_inserted=549502, tup_updated=1060139, tup_deleted=7, tup_fetched=1994768, tup_returned=8597099, blks_read=22454, blks_hit=20711377, wal_records=4740902, wal_fpi=1, wal_bytes=642738733, wal_buffers_full=0, wal_write=103289, wal_sync=103289, wal_write_time=1058.5999999999995, wal_sync_time=44139.16400000002}
Store operations, kafkaEndOffsets (seconds -29.848 to 62.074): {wow.transfer.account.state=811124}

PostgreSQL per second: commits 2753, WAL flushes 1693, WAL flush time 723 ms (max 795 ms), WAL write time 17 ms
