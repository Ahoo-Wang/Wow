---
title: Choose the Stores
description: What each store holds, MongoDB by default, PostgreSQL as an equal, Elasticsearch for search, beside a type's own snapshot store or as it, ClickHouse for analytics, and how to place an aggregate type's data.
---

# Choose the Stores

A service keeps its log, snapshots, timers, checkpoints, leases, failure ledger and reservations in its stores. One store of one type is enough, and most services need nothing more. This page says what each type is for, how to give an aggregate type a search index beside it, and why the log never moves.

## Roles {#roles}

| Store | Holds | Use it for |
| --- | --- | --- |
| MongoDB | Everything: the log, snapshots, timers, checkpoints, leases, failure ledger and reservations; serves snapshot and `events` queries | The default. A replica set, sharded when one is not enough ([MongoDB](./mongodb#recommended-topology)) |
| PostgreSQL | Everything, as MongoDB | An equal choice where PostgreSQL is what you run ([below](#when-postgresql)) |
| Elasticsearch | An aggregate type's snapshots, a copy for its queries or its only snapshot store, and its events through a sink | Searching states and events: full text over the fields marked `@QueryText`, aggregations over many dimensions ([Elasticsearch](./elasticsearch#full-text-search)) |
| ClickHouse | Not a store: a sink of the published streams, and the datasets' warehouse | Analytics across services ([ClickHouse](./clickhouse)) |

## Place an aggregate type's data {#per-aggregate-type}

1. **By default, an aggregate type writes to MongoDB alone.** Its log and its snapshots are there, its commands and state reads read them, and its snapshot and `events` queries run there too.
2. **A type whose states must be searched** keeps its snapshots on MongoDB and adds a copy on Elasticsearch, which its snapshot queries read:

   <!-- sample: fragment -->

   ```yaml
   wow:
     stores:
       main: { type: mongo }
       search: { type: elasticsearch }
     log:
       store: main
     aggregates:
       "[shop.product]":
         query:
           snapshot:
             store: search
   ```

   Its snapshot queries, lists included, read Elasticsearch, which keeps a copy of its snapshots for them. Its commands load their state from MongoDB, and its state reads (`GET …/state`, `minVersion` among them) read MongoDB, so a strong read never waits for Elasticsearch. A page that submits a command and then reloads such a list waits `queryable` ([Elasticsearch](./elasticsearch#reading-your-own-write)).

3. **A type whose event history alone is searched** keeps its snapshots where they are, and names the Elasticsearch store for its `events` queries: `query.events.store: search`. The host starts the event search sink by itself, and no second snapshot store is needed.

## Not a dual write {#not-a-dual-write}

A copy on Elasticsearch is not written beside the aggregate's own snapshot store in the command's path. The command commits to the log, and each snapshot store is written from the log by a subscription of its own: `wow.snapshot` for the default snapshot store (`wow.snapshot.store`, else the log's store), `wow.snapshot:<store>` for each other, each with its own checkpoint. A snapshot store keeps the highest version it was given, so a store that receives a commit twice, or late, ends where the log says. Elasticsearch can lag the aggregate's own store; it never diverges from it for good, and it is rebuilt from the log whenever needed ([Rebuild and Replay](./replay)).

## Elasticsearch is never the log {#elasticsearch-is-never-the-log}

- **Near real time.** A write becomes searchable after a refresh: by default within the index's refresh interval (`refresh: none`), or when its write returns with `refresh: wait-for`. A log's tails must see a commit at once. A read by id sees it when its write returns, a search only after a refresh.
- **No transactions.** A document is replaced whole, guarded by optimistic concurrency; a log needs unique keys on a version and a request, which a search index does not keep.
- **Mappings grow.** Every field of the states and the events is mapped, so the mapping grows with the model, and an index whose mapping must change is rebuilt rather than migrated.

So the host refuses an Elasticsearch store as a log store. It may be an aggregate type's own snapshot store, which the commands and the state reads read by id ([Elasticsearch](./elasticsearch#only-snapshot-store)). The type's state is then stored once, beside a MongoDB log, and its default wait, its lists and its outage are Elasticsearch's. Keep a database's snapshot store with a copy when Elasticsearch should stay off the commands' path: its outage then only makes the search stale.

## The costs {#costs}

- **More writes.** Each snapshot store is written for every commit of its types: two stores, twice the snapshot writes.
- **Versions apart.** A search may show a version older than the state read shows a moment later. Read the state when the answer must be current.
- **Another cluster.** Elasticsearch is a second system to run, size, secure and monitor beside MongoDB.

[Performance](./performance) gives each store's figures in the benchmarks.

## When to pick PostgreSQL {#when-postgresql}

PostgreSQL holds everything MongoDB holds, with the same guarantees, so the choice is about what you run:

- Your organisation runs PostgreSQL, its backups, its monitoring, its people.
- You want the data in SQL: the tables are documented, one per aggregate type ([PostgreSQL](./postgresql#the-tables)).
- A command whose state is not cached reads its snapshot and the log after it in one round trip when the snapshots are in the log's database; MongoDB reads them apart.

PostgreSQL searches no text: add an Elasticsearch copy for a type that needs it, as rule 2 above shows. Size its connections for every node ([Size the pools](./postgresql#size-the-pools)).
