---
title: MongoDB
description: Run a service on MongoDB, which needs a replica set; the collections Wow creates, appends and read concern, read preferences for queries, and schema management.
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
- **Appends.** An append is one insert, without a transaction. A node takes a block of 256 sequences of a partition from its counter and gives them to its appends in turn; the unique keys refuse a version or a request that is already taken. A sequence a node took and did not write gets a placeholder, which the tails step over, so a partition's sequences increase with gaps. Appends to one partition do not contend, and need no queue.

`wow.mongo.append-timeout` (5 seconds by default) bounds an append's insert, below the default wait timeout, so a command appending during an outage is answered before its wait ends.

## The collections

The stores create their collections and indexes on first use, named as on PostgreSQL:

| Collection | Holds |
| --- | --- |
| `wow_log__<context>__<aggregate>` | The log of one aggregate type, one document per committed stream, and the placeholders of sequences no stream took |
| `wow_log__<context>__partition` | One counter document per partition of the context's log: the sequences given out, and the blocks still open |
| `wow_log_aggregate` | Which aggregate collections each context's log has |
| `wow_snapshot__<context>__<aggregate>` | One document per aggregate: its latest snapshot |
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
