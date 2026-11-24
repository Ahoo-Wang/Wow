---
title: MongoDB
description: Run a service on MongoDB, which needs a replica set; the recommended topology, failover, production deployment and sharded clusters, the collections Wow creates, read preferences for queries, and schema management.
outline: deep
---

# MongoDB

On MongoDB, one database holds what a service stores: its log, its snapshots, its timers, and its checkpoints, leases, failure ledger and reservations. Every write goes through a majority, and so does every read of the log, so MongoDB must run as a replica set. This page connects a service and describes what it relies on.

## Connect a service

Put `wow-mongo` on the class path and set `spring.mongodb.uri`: the host makes a synchronous driver `MongoClient` of it and chooses MongoDB:

```yaml
spring:
  mongodb:
    uri: mongodb://mongo-1,mongo-2,mongo-3/?replicaSet=rs0
```

An application with Spring Boot's MongoDB module (`spring-boot-starter-mongodb`) gets the client Spring Boot makes of the same property instead. One that needs settings of its own defines a `MongoClient` bean, which the host keeps; Wow's benchmark server does, to add its driver command listeners:

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
 * The `mongo` profile: the application's `MongoClient` on `spring.mongodb.uri`, which selects the MongoDB backend. A
 * bean of its own, not the one the host makes of the property, because it takes the application's
 * driver [CommandListener]s, which no property can add: none in the server, the round-trip counter in ComponentBench.
 * It is built with the stores' [MongoPoolGauge], as an application's own client is, so the stores bound their queries
 * by its pool and read their write pressure from it. The `postgres` profile needs no bean: the host makes its pool of
 * `spring.datasource.*`.
 */
@Configuration(proxyBeanMethods = false)
@Profile("mongo")
class MongoBackend {
    @Bean(destroyMethod = "close")
    fun mongoClient(
        environment: Environment,
        listeners: ObjectProvider<CommandListener>,
        gauge: MongoPoolGauge,
    ): MongoClient = MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(environment.getRequiredProperty("spring.mongodb.uri")))
            .applyToConnectionPoolSettings(gauge::applyTo)
            .apply { listeners.orderedStream().forEach(::addCommandListener) }
            .build(),
    )
}
```

The connection string names the replica set, `mongodb://mongo-1,mongo-2,mongo-3/?replicaSet=rs0`. The data goes to the database `wow.mongo.database`: by default the database the connection string names, else `wow`; give each service a database of its own.

## Recommended topology

MongoDB is the default store of a Wow service ([Choose the Stores](./choosing-storage)). Run it so:

- **A replica set of three data-bearing members**, in three failure domains, without an arbiter ([In production](#in-production)). Outside development, a log on a standalone `mongod` refuses to start: the log writes with majority, which a standalone server cannot keep.
- **Write concern `majority`, throughout.** Wow writes every collection with majority, the log and the snapshots, timers, reservations, checkpoints, leases and ledger alike, whatever the client's default ([A replica set is required](#a-replica-set-is-required)): a failover loses no acknowledged write.
- **Queries on a secondary if they compete with the appends**: `wow.mongo.query-read-preference: secondaryPreferred` ([Queries and read preferences](#queries-and-read-preferences)). Commands, state reads and every other read of Wow's stores (the log, checkpoints, leases, timers, reservations, the ledger) always read the primary, whatever `readPreference` the connection string sets.
- **A sharded cluster** once one replica set cannot carry the writes or the data: the log and the timers are sharded by partition, the snapshots and the reservations by tenant and a hashed key ([Sharded clusters](#sharded-clusters)).

## A replica set is required

- **Majority.** Every write is acknowledged by a majority (`w: majority`): the appends, and the snapshots, timers, reservations, checkpoints, leases and ledger entries too, whatever the client's default write concern. The log's reads, the subscriptions' tails and the `events` queries among them, read what a majority has committed (`readConcern: majority`); the other stores, and the snapshot queries, read with the client's read concern. A subscription therefore never acts on a commit a failover later rolls back, and an `events` query never shows one. A snapshot is folded from committed entries, so the worst a failover shows of one is an older state. The cost is about one replication round trip before an entry reaches the tails. A replica set whose majority cannot commit, such as one with an arbiter and a member down, stalls the tails until it can. A standalone server has no majority: outside development a log on one refuses to start, so even a single node runs as a single-member replica set.
- **Appends.** An append is one insert, without a transaction. A node takes a block of 256 sequences of a partition from its counter and gives them to its appends in turn; the unique keys refuse a version or a request that is already taken. A sequence a node took and did not write gets a placeholder, which the tails step over, so a partition's sequences increase with gaps. Appends to one partition do not contend; under load, the node gathers a partition's appends into one insert per collection.
- **Hand-offs.** A partition's tail does not step over a missing sequence: an append may still land there. A node that hands a partition off closes the rest of its block with one placeholder, and a command that runs without the partition's lease while the partition moves takes exactly the sequences it writes, so the new owner's tail reads on without waiting. A node that stops without handing off, a crash or a lost lease, leaves its block open: the tails wait 1 second at its first missing sequence, then seal the block with placeholders. Expect that pause once per open block after a crash, not after a rolling restart.

`wow.mongo.append-timeout` (5 seconds by default) bounds an append, all its round trips together: the reservation of a block, the read of the aggregate's previous stream, the insert, the lookups after a refusal and the placeholders. It is below the default wait timeout, so a command appending during an outage is answered before its wait ends. Every other call of a store is bounded by `wow.mongo.operation-timeout` (30 seconds by default, a store's `operation-timeout`, `wow.stores.<name>.operation-timeout`, overrides it), and the schema's commands (format records, indexes, sharding) by 5 minutes: a reply lost on the way, in a failover or behind a proxy, fails the call as a transient error instead of blocking it. Queries keep their own budget.

The host builds the `MongoClient` it makes, or Spring Boot's, with a `MongoPoolGauge`: the queries of the stores over it are bounded by the client's pool (a quarter of it, 25 for the driver's default 100 connections, rising to half of it as the writes allow), and the check-outs waiting for a connection count in the stores' write pressure. An application that builds its own client adds the gauge to it (`MongoClientSettings.builder().applyToConnectionPoolSettings(gauge::applyTo)`) and declares it as a bean; a store's `pool-gauge` names it when there are several. A store without one says so at startup, and its queries take the default bound of 16.

## Failover

When the primary steps down or fails, the replica set elects another within seconds (about 2 s on the test cluster for a step-down; up to `electionTimeoutMillis`, 10 s by default, for a member that stops answering).

A failover loses nothing Wow acted on. The subscriptions read only what a majority has committed, and a majority's commit survives any failover the set survives. So no saga's derived command, Kafka message, projection's write or snapshot comes from a commit the new primary lacks. On PostgreSQL, synchronous replication gives this guarantee ([Failover](./postgresql#failover)); here the store gives it.

What a failover does to the appends in flight:

- An append acknowledged as committed is never lost: a majority has it, and the new primary has it too. So is any other write Wow made: a reservation, a timer, a checkpoint.
- An append whose acknowledgement the failover interrupted is unknown: it may have landed, and it may be rolled back with the old primary. The node writes a placeholder at its sequence once it can. When the placeholder meets the stream, the node takes the sequence as written only once a majority read finds the stream there: a stream the failover rolls back leaves the sequence empty, and the tail fills it after its grace. The command is answered as unknown, and a retry with the same request ID finds the stream or commits it once.
- An append the server refused before writing is answered as a retryable `wow.storage.unavailable`; it wrote nothing.
- The driver retries a write once after a failover (retryable writes, on by default); most appends of the election's seconds wait for it and commit.
- Leases and checkpoints survive: an owner renews its leases on the new primary, and no other node takes them while they are valid.

The backend's integration tests run on a replica set of three every night: appends from eight writers while the primary steps down, and while it is killed and restarted, then the partition's tail read to its end (`test/mongo-cluster/cluster.sh test rs`).

### A tail that waits at a sequence no block holds {#a-tail-that-waits-at-a-sequence-no-block-holds}

A log written by an earlier pre-release may hold a sequence its node took as written on a duplicate key. The primary that answered had the write, and then rolled it back in a failover. Every tail of the partition stops before such a sequence. No open block holds it, so no append can land there any more, and a tail never steps over a missing sequence. The node warns after about ten seconds of waiting, and once a minute after that, naming the partition, the sequence and the placeholder that repairs it:

```text
The tail of partition 3 of shop has waited at sequence 1041, which no open block holds, for 10 graces: …
```

To repair it, first check that no log collection of the context holds a stream at that position. Then insert the placeholder the warning names into one log collection of the context, with a majority write concern:

```js
db.getCollection("wow_log__shop__order").insertOne(
  { _id: NumberLong("6755399441056785"), partition: 3, sequence: NumberLong("1041"), gap: "recovered", gap_node: "operator" },
  { writeConcern: { w: "majority" } }
)
```

`_id` is the position: the partition times 2<sup>51</sup>, plus the sequence. The tails go on at their next read. Nothing repairs such a sequence on its own. A log this version writes takes a sequence as written only on what a majority holds, so it leaves none behind.

## In production

- **Three data-bearing members, no arbiter.** Majority writes and reads need two of three members up; an arbiter counts for elections but holds no data, so with one data member down the majority cannot commit and the tails stall. Put the members in three failure domains (zones or racks).

  A node outside development warns at start, once per store, when its client connects to a replica set that lists an arbiter (a primary, a secondary and an arbiter, say):

  ```text
  The client of wow.mongo connects to a replica set with an arbiter (mongo-3:27017): while one of its data-bearing members is down, MongoDB cannot acknowledge a majority write, so the log's appends and the lease writes wait until they time out, the nodes stop their partitions, and the subscriptions' tails stall. Replace the arbiter with a third data-bearing member, or bring a member that goes down back at once.
  ```

  It is a warning, not a refusal: such a set works while both data-bearing members are up. Through a `mongos` the shards' members are not visible, so a sharded cluster is not warned: check that no shard has an arbiter.
- **Latency.** Each append waits for a majority, one replication round trip; keep the members within a few milliseconds of each other. A distant member for disaster recovery takes `priority: 0` and `votes: 0` so it neither becomes primary nor slows the majority.
- **Disk and cache.** The log is append-mostly; give the members local SSDs with `fsync` that does not stall, and size WiredTiger's cache (half the RAM less 1 GB by default) to hold the snapshot collections' working set and the log's recent entries.
- **Oplog.** Size it for the longest maintenance a secondary may be away (hours to a day): a secondary that falls off the oplog needs a full resync.
- **One database per service.** It holds that service's log and stores; several services may share a replica set or a cluster.

## What an idle node reads {#what-an-idle-node-reads}

A node reads its partitions even when nothing is sent to it, for the entries other nodes appended there, which its own commits do not bring its tails (this node's own commits reach them at once):

- **Every half `wow.log.max-poll-interval`** (0.5 s by default), one `find` of the counters of all the partitions the node owns, per log group. A partition whose counter lists another node's block, or single-sequence reservation, that the previous `find` did not is read: its head, then its entries, once its tail has been idle half that interval. A node's first `find` after a tail opens reports each partition with another node's block past the tail once.
- **Every 10 s** (or `max-poll-interval` when longer), the heads of those partitions in one aggregation, a `$unionWith` per aggregate collection and partition: the backstop for what the counters cannot show, an entry appended into a block already seen, by a node that went on writing after the partition moved. A tail it finds behind reads it and logs a warning.
- **Every `wow.node.sample-interval`** (10 s), the node's sampler reads the same heads in one aggregation, for the lag it reports, and its health check reads one partition's head of each log, to see the store answer.

These are one node's reads, per log group: each node reads the partitions it owns, so the service's reads grow with its nodes. With the defaults, a node makes about two counter `find`s a second and three aggregations every 10 s, and three nodes make three times that.

So an entry another node appends through a block or reservation the node has not seen is read within `max-poll-interval` of a pause in its tail, and one appended into a block already seen within the backstop's 10 s. Leases, membership and the timer loop read on their own schedules beside these.

## Sharded clusters

On a sharded cluster the service connects to the mongos routers (`mongodb://mongos-1,mongos-2/`), and the stores shard their large collections when they create them:

| Collection | Shard key | Why |
| --- | --- | --- |
| `wow_log__<context>__<aggregate>` | `{partition: 1}` | A partition's entries are one range; the version and request keys lead with the partition, so they stay unique, and a read by aggregate or a tail reads one shard |
| `wow_snapshot__<context>__<aggregate>` | `{tenant_id: 1, aggregate_id: "hashed"}` | A snapshot's `_id` is its key; new aggregates spread over the shards, a load reads one, a query of one tenant reads that tenant's chunks |
| `wow_timer__<context>__<aggregate>` | `{partition: 1}` | A timer is in its owner's partition; the key `(partition, owner, key)` leads with it, so it stays unique, a read or write by owner reaches one shard, and a node's due read the shards of its partitions. A resume's read of a target's parked timers reads every shard |
| `wow_reservation__<context>__<aggregate>` | `{tenant_id: 1, key: "hashed"}` | A key's `_id` is `{t: tenant, n: namespace, k: key}`; keys spread over the shards, and a reserve, a confirmation or a lookup of a key reads one |
| `wow_claim__<context>__<aggregate>` | `{tenant_id: 1, request_id: "hashed"}` | A claim's `_id` is `{t: tenant, r: request id}`; claims spread over the shards, and a claim reads and writes one, where its `_id` keeps one claim per request |

- A new log or timer collection is split at each partition and its chunks spread over the shards in turn, while it is empty; a new snapshot collection starts with one chunk, and the balancer spreads it by data size.
- The counters, the registries, the checkpoints, leases and ledger are small and stay unsharded on the database's primary shard.
- The connection's user needs to shard: the `clusterManager` role, or `enableSharding` and `splitChunk`/`moveChunk` on the database.
- `wow.mongo.sharding: none` (or a store's `sharding: none`) leaves the collections unsharded, for operators who shard them; so do `wow.ddl: validate` and `none`.
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
| `wow_claim__<context>__<aggregate>` | The claims of the creations of one aggregate type that name no aggregate id, when the type's generator cannot aim at a partition: one document per (tenant, request id), the id every attempt of the request takes |
| `wow_snapshot__<context>__<aggregate>` | One document per aggregate: its latest snapshot, whose `_id` is `{t: tenant, a: aggregate id}` |
| `wow_snapshot_table` | The state revision each snapshot collection holds, whether it caught up with the log, and who fills it from the log's beginning |
| `wow_timer__<context>__<aggregate>` | The scheduled commands of one aggregate type, one document per timer, each in its owner's partition |
| `wow_timer_aggregate` | Which timer collections each context has; the due read unions them in one aggregation |
| `wow_checkpoint`, `wow_lease`, `wow_lease_member` | Where each subscription stands, and which node owns which partition |
| `wow_reservation__<context>__<aggregate>` | The unique keys aggregates of one type reserved, one document per (tenant, namespace, key), with its owner |
| `wow_failure_ledger` | The parked aggregates |
| `wow_format` | The format version of each structure |

A log collection keeps an index for each access attribute an `events` query under an access policy reads, `access_<name>`: a declared attribute's leads with the tenant and holds every stream. The built-in `owner` and `space` are indexed differently. A stream that has one also stores it scoped by its tenant in its own field, `access_key` (the tenant's length, a colon, the tenant, then the value), and `access_owner` and `access_space` are sparse indexes of those keys. So a stream of an aggregate with neither, such as one a job creates, adds no index entry, and an equality or an `$in` on the owner or the space reads the tenant's entries alone, inside an `anyOf` as well. A negation and `isNull` read the attribute itself, by no index of its own, as on PostgreSQL. A query of every tenant (`Wow-Scope: tenant=*`) has no tenant to scope a key with, so its equality on the owner or the space scans the collection. `access_key` is the store's own: no record and no SPI reads it.

**Creation ids.** The log's request key leads with the partition, so a request is unique within a partition only. A creation that names no aggregate id takes its id at admission, so two attempts of one request (a retry sent while the first attempt is still deciding) could take ids in two partitions and both commit. Such a creation therefore takes an id in its request's partition, a function of the aggregate type, the tenant and the request id alone: every attempt of the request meets the others at the request key, and the request commits once. A request committed before (a retry after the first attempt committed, a log imported by `migrate-data`) is found first, since admission looks the request up in the log before it takes an id.

- **A generator that aims**: Wow's default UUIDv7 one (no `wow.aggregates.[…].id-generator` names another for the type, and neither the `GlobalIdGenerator` bean nor the `wowAggregateIdGenerator` bean is replaced), or an application's own that implements `AggregateIdGenerator.nextIn`: admission asks it for an id in the request's partition, and nothing is written. Wow's chooses the id's last four hexadecimal digits so that it falls there, in one pass and with nothing allocated but the id; the id is a UUIDv7 still, with 58 random bits of its 74. It costs no round trip, on PostgreSQL as on MongoDB.
- **A generator that cannot aim** (one an `id-generator` names, such as CosId's, or a replaced generator bean that does not implement `nextIn`): admission claims the id for the request before it routes the command. The first attempt's claim holds, and every later attempt of the request takes that id, so they meet in one aggregate. A claim is written by a majority within the append timeout, as an append is: a claim a failover rolled back would let a retry draw again. Claims are never removed, since nothing bounds how long an attempt may decide; each is about 100 bytes. A request committed without a claim is still found by the lookup before it. PostgreSQL keys a request per table, and keeps no claims.

Since the aggregates of id-less creations land in their requests' partitions, they spread as the request ids do. Wow's own request ids are UUIDv7 ones; a caller that sends its own spreads as its ids are made, and with at most 32 partitions, request ids that differ only in the high bits of a character (`a` and `A`) share one partition, as aggregate ids do.

During a rolling upgrade from 10.0.0-beta.12 or earlier, a node of the old version claims every creation's id (or, before beta.12, draws it without a claim), while a node of the new version aims a default generator's ids at the request's partition without reading the claims: two attempts of one request that reach nodes of the two versions may still commit two aggregates, until every node runs the new version. The same holds while a rolling restart changes the generator a type's `id-generator` names. The ids of a generator that cannot aim are claimed by both.

A new aggregate type's collection is created the first time the log touches it; it needs no restart. Decimals are stored as `Decimal128`, exactly; an append whose payload holds a number MongoDB cannot store exactly is refused.

`wow.ddl` decides whether the stores create their collections and indexes (`auto`, the default), only check them (`validate`), or neither (`none`), as on PostgreSQL ([PostgreSQL](./postgresql#manage-the-schema)).

The registries `wow_log_aggregate` and `wow_timer_aggregate` are data, not schema. A store records each log and timer collection there under every `ddl`, the first time it uses it: a tail, `head` and the due read read the collections a registry lists. So the store's account needs `insert` on both registries even under `validate` and `none`. A store that cannot write a record refuses that first use, naming the document to insert. A collection without its record (a database restored without the registries' documents, or collections made by hand) is recorded by the first node that uses its aggregate type. Until then, a tail that meets an open block naming it waits there rather than seal the block, and warns, naming the document.

The failure ledger reads what is due by its key index, and creates no index on the partition and the next attempt. A `wow_failure_ledger` created by an earlier build keeps its `ledger_due` index, which nothing reads and every ledger write still maintains; Wow drops nothing on an existing database, and an operator may drop it: `db.wow_failure_ledger.dropIndex("ledger_due")`.

## Queries and read preferences

Snapshot and `events` queries read the primary by default, beside the appends. A free filter over a large collection can scan; send such queries to a secondary with a read preference:

| Key | Applies to |
| --- | --- |
| `wow.mongo.query-read-preference` | Every snapshot and `events` query of the MongoDB stores, such as `secondaryPreferred` |
| `wow.stores.<name>.query-read-preference` | One store's snapshot and `events` queries |

A secondary may lag the primary; a read that must see a write asks for its version instead ([Configuration Keys](../reference/configuration#wow-query)).

## Several databases

Named store instances place aggregate types on different databases or clusters, as on PostgreSQL: `wow.stores.<name>.type: mongo`, with the `client` bean and the `database` each uses ([PostgreSQL](./postgresql#several-databases)). Two log stores may not share one database.

## Backups

The log is the only fact; snapshots, read models and the warehouse are rebuilt from it. Back up the log collections with the creation claims, checkpoints, leases, ledger, reservations and timers, as one consistent backup of the database.
