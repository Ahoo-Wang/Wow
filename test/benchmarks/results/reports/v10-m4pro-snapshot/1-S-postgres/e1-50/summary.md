# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-225058-run-postgres-n1-saturation.

Open loop at 10602.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4905.8/s | 294345 | 341775 | 790.5 ms | 1417.2 ms | 1785.9 ms | 2211.8 ms | 3179.5 ms | 2293.8 ms |

Errors: {exception-IOException=12, exception-SocketException=170, http-429-wow.command.busy=341593}

Load generator: 2.35 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.44 | 1270 MB | 906 MB | 334 | 3.464 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.6% | 158.6% | 2.221GiB / 15.6GiB |
| wow-e2e-bench-postgres | 238.1% | 311.2% | 1.045GiB / 15.6GiB |

Store operations, postgres (seconds -0.065 to 60.936): {epoch=61.000537157058716, xact_commit=416572, xact_rollback=0, tup_inserted=299340, tup_updated=168111, tup_deleted=0, tup_fetched=7947594, tup_returned=7954152, blks_read=6472662, blks_hit=9414762, wal_records=1587315, wal_fpi=14, wal_bytes=269108405, wal_buffers_full=0, wal_write=9250, wal_sync=9250, wal_write_time=834.7620000000006, wal_sync_time=11610.152999999933}
Store operations, kafkaEndOffsets (seconds -30.041 to 62.407): {wow.transfer.account.state=488683}

PostgreSQL per second: commits 6829, WAL flushes 152, WAL flush time 190 ms (max 521 ms), WAL write time 14 ms
