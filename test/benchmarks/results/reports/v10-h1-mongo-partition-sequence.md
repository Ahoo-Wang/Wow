# V10 Spike H1 (MongoDB): Partition Sequences In Transactions

Spike H1 of the V10 design (`documentation/designs/v10/decisions.md` on the `v10` branch) asks whether assigning each log entry a partition sequence, in commit-visible order, can carry the commit rate. The PostgreSQL half is in `v10-h1-postgres-partition-sequence.md`. This is the MongoDB half: a first-class backend by decision D11.

MongoDB can carry it only when one owner appends to each partition. Contending writers collapse a partition, as design finding F1 predicted. When a partition's owner queues appends and commits each batch in one transaction (group commit), the log reaches about 4,400 to 5,600 appends a second, roughly half of plain inserts. That is about a sixth of PostgreSQL's rate on the same machine.

## What Is Measured

The code is `wow-mongo`, `PartitionSequenceThroughputSpike`. It runs only with `-Dwow.spike.h1=true`, against a single-node replica set started by Testcontainers on a laptop's Docker.

Each append commits one stream: version 1 of a new aggregate, with one small event. It goes through `MongoEventLog` with `ReadConcern.SNAPSHOT` and `WriteConcern.MAJORITY`, running on virtual threads. Each scenario runs 2,048 warm-up appends, then measures 16,384.

`MongoEventLog.append` is one multi-document transaction with three steps:

1. Read the aggregate's version.
2. `$inc` the partition's counter document.
3. Insert the stream at that sequence.

Two transactions that modify the same counter conflict. The later one aborts and the driver retries it, so the sequence follows commit order and never skips a number.

`appendAll` commits a batch of streams of one partition in one transaction. If any stream in the batch conflicts, the batch falls back to one transaction per stream, so a single conflict cannot fail its neighbours (finding F2).

| Scenario | What varies |
|---|---|
| single writer | One writer on one partition: the per-partition ceiling. |
| one partition, 32 contending writers | Many writers modify the same counter document. |
| 64 partitions, 32 / 128 writers | Writers are spread over the partitions, with no coordination. |
| owner-serialized | As above, but each partition's appends first take a local lock, as a partition owner would (F1). |
| owner group commit | Each partition's owner queues the appends and commits up to 64 per transaction with `appendAll`. |
| baseline: plain insert | The same document inserted with no transaction, counter or version check: the database's own ceiling. |

## Results

| Scenario | Partitions | Writers | Appends/s | p50 (µs) | p99 (µs) |
|---|---:|---:|---:|---:|---:|
| single writer | 1 | 1 | 509 | 1,950 | 3,650 |
| one partition, 32 contending writers | 1 | 32 | 103 | 215,440 | 1,396,438 |
| 64 partitions, 32 writers | 64 | 32 | 2,754 | 10,297 | 29,696 |
| 64 partitions, 128 writers | 64 | 128 | 1,197 | 78,171 | 458,706 |
| 64 partitions, 128 writers, owner-serialized | 64 | 128 | 3,010 | 34,096 | 138,935 |
| one partition, 32 writers, owner group commit | 1 | 32 | 5,634 | 4,784 | 15,847 |
| 64 partitions, 128 writers, owner group commit | 64 | 128 | 4,443 | 27,571 | 50,401 |
| baseline: plain insert, no partition counter | 64 | 128 | 9,866 | 12,286 | 24,121 |

An earlier run, without group commit, gave the same shape: 595/s for a single writer, 75/s for one contended partition (p99 2 s), 3,151/s owner-serialized, and 10,191/s for plain inserts.

## Reading

1. **Contention collapses, it does not queue (F1 confirmed).**
   - On one partition, 32 writers get a fifth of what one writer gets, with a p99 of 1.4 s.
   - On 64 partitions, going from 32 to 128 writers more than halves throughput.
   - MongoDB aborts conflicting transactions instead of making them wait, so throughput falls as more writers contend.
2. **Owner serialization removes the collapse, and group commit pays for the transaction.**
   - Taking a local lock before each append only restores single-writer behaviour per partition.
   - Committing a queued batch in one transaction shares the transaction's round trips and its majority write: one partition reaches 5,634/s, eleven times its single-writer rate.
3. **More partitions do not help here.**
   - With 128 writers over 64 partitions, each owner's batch holds about two streams, so batching gains little.
   - Batches are larger, and throughput higher, when each owner has more traffic.
   - P stays at 64 (D28); the runtime's owner queue decides how large batches get.
4. **What it costs.**
   - About 45% to 57% of plain inserts on the same container, and about a sixth of PostgreSQL's single-statement append (about 30,000/s).
   - On this setup that is a few thousand commands a second per context, enough for D11's "first-class, not degraded". A deployment that needs more uses PostgreSQL.

## Consequences

- **D11 stands, with a firm dependency on D6.** MongoDB's log throughput needs Partitioned routing, so that one owner appends to each partition. If H2 overturns Partitioned as the default, the MongoDB backend has to be re-evaluated.
- **`EventLog.appendAll` is in the SPI.** A partition owner calls it with its queued appends. The default implementation appends one stream at a time, so PostgreSQL keeps its single-statement path. The contract tests (`EventLogSpec`) cover batches: each outcome must equal what `append` would have returned, including conflicts and duplicate requests in the middle of a batch.
- **Not measured yet:**
  - Failover of the replica set while appending.
  - Tail latency of subscriptions reading the log.
