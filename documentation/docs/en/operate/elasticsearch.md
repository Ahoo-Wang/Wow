---
title: Elasticsearch
description: Keep a copy of a service's snapshots on Elasticsearch for their queries, and search its events through a sink, while its log and its primary snapshots stay on PostgreSQL or MongoDB.
---

# Elasticsearch

Elasticsearch never holds a Wow log: every aggregate's log stays on PostgreSQL or MongoDB, the source of truth. Elasticsearch gets copies: the snapshots, so their queries run on a search engine, and the event streams, through a sink, so the `events` model can be searched across aggregates. Both can be rebuilt from the log at any time. The module is `wow-elasticsearch`.

## A store of type `elasticsearch`

A store of type `elasticsearch` opens over the application's low-level `Rest5Client`, which Spring Boot's Elasticsearch support configures. Name it beside the log's store, add it to an aggregate's snapshot stores after the store its commands load from, and point the aggregate's queries at it:

<!-- sample: fragment -->

```yaml
wow:
  stores:
    main: { type: postgres }
    search:
      type: elasticsearch
      options:
        index-prefix: shop
  log:
    store: main
  aggregates:
    "[shop.order]":
      snapshot:
        stores: [main, search]
      query:
        snapshot:
          store: search
        events:
          store: search
```

| Key | Sets | Default |
| --- | --- | --- |
| `wow.stores.<name>.client` | The `Rest5Client` bean the store writes through | The only `Rest5Client` |
| `wow.stores.<name>.options.query-client` | A second `Rest5Client`, a connection pool of the queries' own | The store's `client` |
| `wow.stores.<name>.options.index-prefix` | A prefix for every index name: `shop_wow_snapshot__…`, `shop_wow_events__…` | None |
| `wow.stores.<name>.options.refresh` | When a snapshot write becomes searchable: `wait_for`, `true` or `false` | `wait_for` |
| `wow.stores.<name>.ddl` | Whether it creates its indices and maps the state's and the events' body fields | `wow.ddl` |
| `wow.aggregates.[<context>.<aggregate>].snapshot.stores` | The stores of an aggregate's snapshots; the first, its primary, is never Elasticsearch | The log's store |
| `wow.aggregates.[<context>.<aggregate>].query.snapshot.store` | The store its snapshot queries read | Its primary snapshot store |
| `wow.aggregates.[<context>.<aggregate>].query.events.store` | The store its `events` queries read | The log's store |

Each index records its layout in its `_meta` and refuses a store that expects another.

## Snapshot queries

An aggregate whose `query.snapshot.store` names the Elasticsearch store has its snapshot queries served from there, with the same [query language](../reference/query-language) and capability descriptor as any backend. With the default `refresh: wait_for`, a snapshot is searchable when its write returns. The queries of a store run under its query bulkhead: `wow.stores.<name>.query.max-concurrent`, by default a quarter of the write pool's connections per route, at least two, or the whole pool of a `query-client`. A query beyond it waits briefly, then is answered 429 `wow.query.busy`.

## Event search

The event search sink writes each commit as one document of `[<prefix>_]wow_events__<context>__<aggregate>`: the `events` model's record, its system fields, and its events as the nested array `events`, each with its name, revision and body as committed. A document's id is the commit's identity, tenant, aggregate id and version, so a commit written again changes nothing. The body fields are mapped from the Manifest's event schemas; a field two events declare with different types is left unmapped.

An aggregate whose `query.events.store` names the Elasticsearch store has its `events` queries served from that index: an event condition is a nested query, an `expand("events")` aggregation a nested aggregation, and a query that names no aggregate searches the index instead of scanning the log. The index shows what the sink has written, a commit becoming searchable once the sink has written it and the index refreshed, one second by default.

Each sink runs as a subscription named `elasticsearch-sink.<aggregate>`, so it resumes from its checkpoint and can start again from the beginning of the log. The host starts the sinks by itself, with no setting of their own: for each Elasticsearch store that an aggregate's `query.events.store` names, it writes exactly those aggregates' streams into that store, whether or not the service publishes them.

## Store combinations

Each aggregate type picks its stores: its log and its primary snapshots on PostgreSQL or MongoDB (`wow.log.store`, `wow.aggregates.[<context>.<aggregate>].log.store`), a copy of its snapshots and its events search on Elasticsearch, as the sample above sets them. Two consequences:

- **Commands and state reads stay on the primary.** A command loads its state from the primary snapshot store and the log after it, and the state reads (`GET …/state`) read the primary, so neither waits for Elasticsearch's refresh. Each snapshot store is written by a subscription of its own from the log, `snapshot` for the first and `snapshot:<store>` for each other, and keeps the highest version it was given, so the copy only lags and can be rebuilt. The host refuses Elasticsearch as an aggregate's primary snapshot store, or its only one, naming the key.
- **Events queries follow the sink.** An aggregate's `events` queries read Elasticsearch once its `query.events.store` names the store, so they see what the sink has written: a commit is searchable shortly after it commits, not at once. Its snapshots may stay on the log's store; the two settings are independent.

## Rebuild

Both copies come from the log. A snapshot index is rebuilt by rebuilding the aggregates' snapshots ([Rebuild and Replay](./replay)). The events index is filled again by starting its sink subscription from the beginning of the log. Neither needs a backup of its own ([Backup and Recovery](./backup)).

## From 9.x

The 9.x Elasticsearch event store is not a migration source: `migrate-data` reads a 9.x MongoDB event store only, so move the streams to MongoDB within 9.x first ([Migrate the Data](../migrate/data)). 9.x indices are not read either. Give Wow 10 its own `index-prefix` or cluster: once the log is migrated, the snapshots are written from it, and the event search sink, started from the beginning, fills the events index.
