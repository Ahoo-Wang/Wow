# V10 Spike H1 (PostgreSQL): Partition Sequences Under A Row Lock

Spike H1 of the V10 design (`documentation/designs/v10/decisions.md` on the `v10` branch) asks whether assigning each log entry a partition sequence in commit-visible order can carry the commit rate. For PostgreSQL it can: on a laptop's Docker, the single-statement append reaches about 30,000 appends a second over 64 partitions, about 70% of what plain inserts of the same rows reach, and the full log contract holds.

## What Is Measured

`backends/wow-postgres`, `PartitionSequenceThroughputSpike` (runs only with `-Dwow.spike.h1=true`). Each append commits one stream (version 1 of a new aggregate, one small event) through `PostgresEventLog`, from coroutine writers on a HikariCP pool of 64 connections; 2,048 warm-up appends, then 16,384 measured.

| Scenario | What varies |
|---|---|
| single writer | one writer, one partition: the per-partition ceiling |
| one partition, 32 contending writers | many writers lock the same counter row |
| 64 partitions, 32 / 128 writers | writers spread over partitions |
| owner-serialized | as above, but each partition's appends are serialized by a local lock first, as a partition owner would (design finding F1) |
| baseline: plain insert | the same row inserted with no counter and no version check: the database's own ceiling |

Two append implementations were measured:

1. **Three round trips** (first draft): a transaction of `UPDATE … RETURNING` on the partition counter, `INSERT … SELECT … WHERE` with the version check, `COMMIT`.
2. **One statement** (kept): one autocommitted statement with two CTEs, the version read and the counter bump guarded by it, then the insert (`PostgresEventLogTables.append`). A version that does not fit bumps nothing; a concurrent append of the same version fails on the unique version key and rolls back its bump, so no sequence is ever skipped.

## Results

Appends per second (p50 / p99 latency in µs):

| Scenario | Three round trips | One statement |
|---|---:|---:|
| single writer | 2,224 (440 / 587) | 5,169 (190 / 304) |
| one partition, 32 contending writers | 2,371 (9,172 / 59,654) | 8,374 (2,535 / 17,297) |
| 64 partitions, 32 writers | 14,834 (1,961 / 4,797) | 29,793 (786 / 4,940) |
| 64 partitions, 128 writers | 14,646 (8,015 / 18,713) | 27,622 (3,821 / 13,793) |
| 64 partitions, 128 writers, owner-serialized | 16,511 (6,142 / 23,638) | 30,933 (2,706 / 16,024) |
| baseline: plain insert | 46,545 (2,292 / 7,360) | 44,267 (2,435 / 8,136) |

An earlier run of the three-round-trip version on the same machine measured 1,369 appends a second for the contended single partition, with a p99 of 179 ms; run-to-run variance on Docker Desktop is that large for contended locks.

## Findings

- **H1 holds for PostgreSQL.** The partition counter costs about 1.4 times a plain insert once the append is one statement; the log contract (`EventLogSpec`: version continuity, tenant-scoped request ids, one winner among concurrent appends, `tail` skipping nothing under ten interleaved writers) passes against it.
- **Round trips, not the lock, were the cost.** Folding the append into one statement doubled throughput and cut the single-writer latency from 440 µs to 190 µs.
- **A partition's ceiling is one commit at a time**, about 5,000 a second here; partitions multiply it until the database itself is the bound. 64 partitions reach about 30,000 a second on this machine, so the partition count is the throughput knob the design describes (D28).
- **Owner serialization helps even on PostgreSQL** (F1): contending writers on one partition queue on its row lock; serializing them first raised throughput and kept latency lower. It matters more on MongoDB, where transactions abort on conflict instead of queueing.
- **JDBC on virtual threads is enough** (D27): no reactive driver is needed to reach the database's ceiling.

## Reproduce

- Source: the `v10` branch at `69344c8fbf` plus the pull request that adds `wow-postgres` and this report.
- Command: `./gradlew :wow-postgres:integrationTest --tests '*ThroughputSpike*' -Dwow.spike.h1=true` (writes `backends/wow-postgres/build/spike-h1.md`).
- Environment: Apple M4 Pro, 14 cores, 24 GiB, macOS 27.0.1; Docker Desktop 29.8.2 (Linux VM with 4 CPUs and 5.8 GiB, which bounds every number here); PostgreSQL 17 (`postgres:17-alpine`, default settings, `synchronous_commit` on); Amazon Corretto 21.0.12.1; PostgreSQL JDBC driver and HikariCP from the Spring Boot BOM.
- Numbers from a laptop's Docker VM are a lower bound and vary between runs; compare scenarios within one run, not across machines.
