---
title: MongoDB
description: Run a service on MongoDB, which needs a replica set; failover, production deployment and sharded clusters, the collections Wow creates, read preferences for queries, and schema management.
outline: deep
---

# MongoDB

On MongoDB, one database holds what a service stores: its log, its snapshots, its timers, and its checkpoints, leases, failure ledger and reservations. Appends and reads go through a majority, so MongoDB must run as a replica set. This page connects a service and describes what it relies on.

## Connect a service

Put `wow-mongo` on the class path and give the application a synchronous driver `MongoClient` bean: the host then chooses MongoDB. Wow's benchmark server defines one in its `mongo` profile:

<!-- sample: test/benchmarks/server/src/main/kotlin/me/ahoo/wow/benchmark/transfer/TransferServer.kt#mongo -->

```kotlin
/**
 * The benchmark's V10 server: the account and the transfer saga on MongoDB (the `mongo` profile) or PostgreSQL (the
 * `postgres` profile), its state stream published to Kafka (`application.yaml`). A node of a cluster takes its ports
 * and node id from the command line (test/benchmarks/README.md).
 */
@SpringBootApplication(proxyBeanMethods = false)
class TransferServer

/**
 * The `mongo` profile: the application's `MongoClient` on `spring.mongodb.uri`, which selects the MongoDB backend. It
 * takes the application's driver [CommandListener]s: none in the server, the round-trip counter in ComponentBench.
 */
@Configuration(proxyBeanMethods = false)
@Profile("mongo")
class MongoBackend {
    @Bean(destroyMethod = "close")
    fun mongoClient(environment: Environment, listeners: ObjectProvider<CommandListener>): MongoClient =
        MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(environment.getRequiredProperty("spring.mongodb.uri")))
                .apply { listeners.orderedStream().forEach(::addCommandListener) }
                .build(),
        )
}
```

The connection string names the replica set, `mongodb://mongo-1,mongo-2,mongo-3/?replicaSet=rs0`. The data goes to the database `wow.mongo.database`, `wow` by default; give each service a database of its own.

## A replica set is required

- **Majority.** Appends are acknowledged by a majority (`w: majority`), and every read, the subscriptions' tails among them, reads what a majority has committed (`readConcern: majority`). A subscription therefore never acts on a commit a failover later rolls back. The cost is about one replication round trip before an entry reaches the tails. A replica set whose majority cannot commit, such as one with an arbiter and a member down, stalls the tails until it can. A standalone server has no majority; even one development node runs as a single-member replica set.
- **Appends.** An append is one insert, without a transaction. A node takes a block of 256 sequences of a partition from its counter and gives them to its appends in turn; the unique keys refuse a version or a request that is already taken. A sequence a node took and did not write gets a placeholder, which the tails step over, so a partition's sequences increase with gaps. Appends to one partition do not contend; under load, the node gathers a partition's appends into one insert per collection (`wow.log.append-queue.*`, on by default).

`wow.mongo.append-timeout` (5 seconds by default) bounds an append's insert, below the default wait timeout, so a command appending during an outage is answered before its wait ends. Every other call of a store is bounded by `wow.mongo.operation-timeout` (30 seconds by default, a store's `options.operation-timeout` overrides it), and the schema's commands (format records, indexes, sharding) by 5 minutes: a reply lost on the way, in a failover or behind a proxy, fails the call as a transient error instead of blocking it. Queries keep their own budget.

## Failover

When the primary steps down or fails, the replica set elects another within seconds (about 2 s on the test cluster for a step-down; up to `electionTimeoutMillis`, 10 s by default, for a member that stops answering). What it does to the appends in flight:

- An append acknowledged as committed is never lost: a majority has it, and the new primary has it too.
- An append whose acknowledgement the failover interrupted is unknown: it may have landed, and it may be rolled back with the old primary. The node writes a placeholder at its sequence once it can; if the stream landed, the placeholder meets it and the stream stays. The command is answered as unknown, and a retry with the same request ID finds the stream or commits it once.
- An append the server refused before writing is answered as a retryable `wow.storage.unavailable`; it wrote nothing.
- The driver retries a write once after a failover (retryable writes, on by default); most appends of the election's seconds wait for it and commit.
- Leases and checkpoints survive: an owner renews its leases on the new primary, and no other node takes them while they are valid.

The backend's integration tests run on a replica set of three every night: appends from eight writers while the primary steps down, and while it is killed and restarted, then the partition's tail read to its end (`test/mongo-cluster/cluster.sh test rs`).

## In production

- **Three data-bearing members, no arbiter.** Majority writes and reads need two of three members up; an arbiter counts for elections but holds no data, so with one data member down the majority cannot commit and the tails stall. Put the members in three failure domains (zones or racks).
- **Latency.** Each append waits for a majority, one replication round trip; keep the members within a few milliseconds of each other. A distant member for disaster recovery takes `priority: 0` and `votes: 0` so it neither becomes primary nor slows the majority.
- **Disk and cache.** The log is append-mostly; give the members local SSDs with `fsync` that does not stall, and size WiredTiger's cache (half the RAM less 1 GB by default) to hold the snapshot collections' working set and the log's recent entries.
- **Oplog.** Size it for the longest maintenance a secondary may be away (hours to a day): a secondary that falls off the oplog needs a full resync.
- **One database per service.** It holds that service's log and stores; several services may share a replica set or a cluster.

## Sharded clusters

On a sharded cluster the service connects to the mongos routers (`mongodb://mongos-1,mongos-2/`), and the stores shard their large collections when they create them (decision D277):

| Collection | Shard key | Why |
| --- | --- | --- |
| `wow_log__<context>__<aggregate>` | `{partition: 1}` | A partition's entries are one range; the version and request keys lead with the partition, so they stay unique, and a read by aggregate or a tail reads one shard |
| `wow_snapshot__<context>__<aggregate>` | `{tenant_id: 1, aggregate_id: "hashed"}` | A snapshot's `_id` is its key; new aggregates spread over the shards, a load reads one, a query of one tenant reads that tenant's chunks |

- A new log collection is split at each partition and its chunks spread over the shards in turn, while it is empty; a new snapshot collection starts with one chunk, and the balancer spreads it by data size.
- The counters, the registry, the checkpoints, leases, ledger, reservations and timers are small and stay unsharded on the database's primary shard.
- The connection's user needs to shard: the `clusterManager` role, or `enableSharding` and `splitChunk`/`moveChunk` on the database.
- `wow.mongo.sharding: off` (or a store's `options.sharding: off`) leaves the collections unsharded, for operators who shard them; so do `wow.ddl: validate` and `none`.
- **A partition is the unit that moves.** Its chunk cannot split, so a partition grows past the chunk size and the balancer leaves it where it is. When adding shards, move partitions with `moveChunk` (with `forceJumbo`), or set the balancer's `attemptToBalanceJumboChunks`. With the default 64 partitions, each of S shards takes about 64 / S; a hot partition is a hot shard.
- To give services their own shards, set each database's primary shard (`enableSharding` with `primaryShard`) before the service starts.

The backend's integration tests and TCK also run every night on a sharded cluster of two shards of three (`test/mongo-cluster/cluster.sh test sharded`).

## The collections

The stores create their collections and indexes on first use, named as on PostgreSQL:

| Collection | Holds |
| --- | --- |
| `wow_log__<context>__<aggregate>` | The log of one aggregate type, one document per committed stream, and the placeholders of sequences no stream took |
| `wow_log__<context>__partition` | One counter document per partition of the context's log: the sequences given out, and the blocks still open |
| `wow_log_aggregate` | Which aggregate collections each context's log has |
| `wow_snapshot__<context>__<aggregate>` | One document per aggregate: its latest snapshot, whose `_id` is `{t: tenant, a: aggregate id}` |
| `wow_snapshot_table` | The state revision each snapshot collection holds |
| `wow_timer__<context>` | The context's scheduled commands |
| `wow_checkpoint`, `wow_lease`, `wow_lease_member` | Where each subscription stands, and which node owns which partition |
| `wow_failure_ledger`, `wow_reservation` | The parked aggregates, and the reserved unique keys |
| `wow_format` | The format version of each structure |

A new aggregate type's collection is created the first time the log touches it; it needs no restart. Decimals are stored as `Decimal128`, exactly; an append whose payload holds a number MongoDB cannot store exactly is refused.

`wow.ddl` decides whether the stores create their collections and indexes (`auto`, the default), only check them (`validate`), or neither (`none`), as on PostgreSQL ([PostgreSQL](./postgresql#manage-the-schema)).

## Queries and read preferences

Snapshot and `events` queries read the primary by default, beside the appends. A free filter over a large collection can scan; send such queries to a secondary with a read preference:

| Key | Applies to |
| --- | --- |
| `wow.query.events.mongo-read-preference` | Every `events` query, such as `secondaryPreferred` |
| `wow.stores.<name>.query.read-preference` | One store's snapshot and `events` queries |

A secondary may lag the primary; a read that must see a write asks for its version instead ([Configuration Keys](../reference/configuration#wow-query)).

## Several databases

Named store instances place aggregate types on different databases or clusters, as on PostgreSQL: `wow.stores.<name>.type: mongo`, with the `client` bean and the `database` each uses ([PostgreSQL](./postgresql#several-databases)). Two log stores may not share one database.

## Backups

The log is the only fact; snapshots, read models and the warehouse are rebuilt from it. Back up the log collections with the checkpoints, leases, ledger, reservations and timers, as one consistent backup of the database.
