# V10 MongoDB on a cluster: sharding the log and the snapshots (D277)

Roadmap §6 item 18. The MongoDB backend verified on a replica set of three and a sharded cluster, and the layout that lets it shard (decision D277): the log's version and request keys led by the partition, a snapshot's `_id` its key, the collections sharded on a mongos. Measured on the local machine of the [local baseline](v10-local-baseline.md) (Apple M4, Docker Desktop) on 2026-10-25; the scripts and their output are in [`v10-mongo-sharding/`](v10-mongo-sharding/).

## The constraint

A sharded collection keeps a unique index unique only if the index leads with the shard key, and `_id` unique within a shard only. Probed on a sharded cluster of two shards:

| Shard key | With | Result |
| --- | --- | --- |
| log `{partition: 1}` | the version key `(aggregate_id, tenant_id, version)` | refused |
| log `{partition: 1}` | the keys led by the partition | sharded |
| log `{_id: 1}` (the position, D276) | the version key, led by the partition or not | refused |
| snapshots `{aggregate_id: "hashed"}` | the key `(tenant_id, aggregate_id)` | refused |
| snapshots `{tenant_id: 1, aggregate_id: "hashed"}` | the key `(tenant_id, aggregate_id)` | sharded |

An aggregate lives in one partition, so a version key led by the partition means what it meant; a request ID reused by two aggregates of one type in different partitions is no longer a duplicate (D277 ②, as D55 already made reuse across types the caller's error). The position `_id` embeds the partition, so all of one partition's entries are on one shard and its `_id` stays unique.

## The layouts' cost

The store's writes, 8 workers at once, each layout twice in the order A B B A, mongod's CPU per operation ([`run-layouts.sh`](v10-mongo-sharding/run-layouts.sh), [`layouts.txt`](v10-mongo-sharding/layouts.txt)):

| | `main` | D277 |
| --- | --- | --- |
| Log: insert, 400,000 streams in batches of 64 | 21.2, 23.7 µs | 18.6, 19.4 µs |
| Log: the latest stream of 200,000 aggregates | 93.2, 114.4 µs | 107.3, 111.0 µs |
| Log: indexes | 61–65 MB | 53–57 MB |
| Snapshots: upsert of 200,000 new aggregates | 224.4, 254.8 µs | 220.7, 249.4 µs |
| Snapshots: upsert of version 2 over them | 207.9, 234.4 µs | 227.1, 233.9 µs |
| Snapshots: a load by key | 101.5, 123.9 µs | 126.8, 126.2 µs |
| Snapshots: indexes | 28.7 MB | 18.6–26.8 MB |
| Snapshots: data | 55.5 MB | 60.3 MB |

- The CPU per operation is the same within the order's drift (the first run of each pair is the fastest): a snapshot write is one call, about 200 µs of fixed cost, whatever the trees.
- The indexes are smaller: the log's partition-led keys by 12%, the snapshots by one tree (the unique key, 12 MB). The snapshot documents grow by their `_id`, 25 bytes each.
- On a sharded cluster the log keeps a fourth tree, `(partition, _id)`, the shard key's index.

## The cluster verification

`test/mongo-cluster/cluster.sh test rs|sharded` starts a cluster in one container, each member a process on its own port named `localhost:<port>` (so the host reaches each by its name), restarted when it stops, and runs `:mongo:integrationTest` against it through `wow.mongo.url`:

| Cluster | Tests | Skipped |
| --- | --- | --- |
| The default container, one member | 455 passed | 8 (H1, the sharded and failover tests) |
| A replica set of three | 455 passed | 5 (H1, the sharded tests) |
| Two shards of three behind mongos | 455 passed | 5 (H1, the profiler, the failover tests) |

The failover tests, eight writers appending new aggregates while the primary fails over, then the partition's tail read to its end:

| Failover | Committed | Unknown | Unavailable | Lost |
| --- | --- | --- | --- | --- |
| `replSetStepDown` | 313 | 4 | 4 | 0 |
| `kill -9` of the primary, restarted | 321 | 0 | 0 | 0 |

- Every committed append was in the tail, once; an unknown one was there once or not at all; no append answered as unavailable was written.
- A lease held across a step-down was renewed by its owner and taken by no other.
- **Found and fixed.** A primary that stepped down while its insert waited for the majority made the insert fail with a write concern error: the write was on the primary, kept if it replicated, rolled back if not. The single insert took it for a failure that wrote nothing and threw `wow.storage.unavailable`; it is unknown now, as the group insert already had it.
- The sharded tests: a new log collection is sharded by partition with one chunk each, spread four and four over the two shards; a read by aggregate and a tail target one shard; with `sharding: off` the collections stay unsharded.

Both clusters run every night (`.github/workflows/mongo-cluster.yml`, dispatched by `nightly.yml`).

## End to end

`main` (A, `6da8fd2c7`) against this branch (B) on one node, MongoDB in memory (`--tmpfs`), fresh primed sessions in the order A B B A, twice ([`sharding-ab.sh`](v10-mongo-sharding/sharding-ab.sh); [`round-1`](v10-mongo-sharding/round-1/COMPARE.md), [`round-2`](v10-mongo-sharding/round-2/COMPARE.md)). E1 at 1,720/s, E3 at 700/s, per operation:

| | A round 1 | B round 1 | A round 2 | B round 2 |
| --- | --- | --- | --- | --- |
| E1 p50 / p99 | 1.10, 0.96 / 7.9, 3.9 ms | 1.71, 0.97 / 15.5, 3.7 ms | 1.01, 0.96 / 4.0, 3.5 ms | 0.97, 0.94 / 3.7, 3.7 ms |
| E1 node CPU | 0.554, 0.574 ms | 0.652, 0.575 ms | 0.522, 0.514 ms | 0.521, 0.519 ms |
| E1 MongoDB CPU | 0.692, 0.515 ms | 0.927, 0.557 ms | 0.619, 0.522 ms | 0.599, 0.609 ms |
| E3 p50 | 3.23, 2.13 ms | 2.34, 1.97 ms | 2.13, 2.08 ms | 2.19, 2.24 ms |
| E3 node CPU | 1.612, 1.741 ms | 1.736, 1.738 ms | 1.583, 1.591 ms | 1.691, 1.650 ms |
| E3 MongoDB CPU | 2.404, 1.496 ms | 1.807, 1.666 ms | 1.816, 1.733 ms | 1.732, 1.754 ms |

- The store's operations per command are the same in every session: 1.00 inserts, 1.30 updates and 0.003 queries per E1, 3.03, 3.77 and 0.008 per E3.
- The costs and latencies differ within the sessions' spread: the first two sessions of round 1 were slower in everything, Kafka's CPU too, which no arm changes; round 2's are within 5% on the node and 6% on MongoDB, where the two sessions of `main` differ by 19% on MongoDB.
- D277 changes nothing measurable on a replica set: the reads by aggregate name one more equality, the snapshots' key is the `_id`; what it buys is a layout that shards.
