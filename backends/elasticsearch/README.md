# wow-elasticsearch

Elasticsearch for Wow: the snapshot store and its queries, and event search through a Sink. Elasticsearch never holds a log: the log of every aggregate stays on PostgreSQL or MongoDB, with its primary snapshots, and Elasticsearch gets copies of the snapshots and of the event streams.

## Stores

A store of type `elasticsearch` opens over the application's low-level `Rest5Client` (Spring Boot's `spring-boot-elasticsearch` auto-configures one):

```yaml
wow:
  stores:
    main: { type: postgres }
    search:
      type: elasticsearch
      client: elasticsearchRestClient # the Rest5Client bean; the only one when not set
      index-prefix: shop # optional: shop_wow_snapshot__..., shop_wow_events__...
  log:
    store: main
  aggregates:
    "[shop.order]":
      snapshot:
        stores: [main, search] # the first, which the commands load from, is never Elasticsearch
      query:
        snapshot:
          store: search # the order's snapshot queries read the copy in Elasticsearch
        events:
          store: search # the order's events queries read the Sink's index, not the log
```

| Key | Values | Default |
| --- | --- | --- |
| `wow.stores.<name>.client` | the `Rest5Client` bean of the store | the only `Rest5Client` |
| `wow.stores.<name>.query-client` | a second `Rest5Client` bean, a pool of the queries' own | the store's `client` |
| `wow.stores.<name>.index-prefix` | prefix of every index name | none |
| `wow.stores.<name>.refresh` | `wait-for`, `immediate`, `none`: when a snapshot write is searchable | `wait-for` |
| `wow.stores.<name>.ddl` | `auto`, `validate`, `none`: the indices and the state and event body fields | `wow.ddl` |
| `wow.aggregates.[<context>.<aggregate>].snapshot.stores` | an aggregate's snapshot stores; the first, its primary, is never Elasticsearch | the log's store |
| `wow.aggregates.[<context>.<aggregate>].query.snapshot.store` | the store an aggregate's snapshot queries read | its primary snapshot store |
| `wow.aggregates.[<context>.<aggregate>].query.events.store` | the store an aggregate's `events` queries read | its log store |

The queries of a store run under its query bulkhead: unless `wow.stores.<name>.query.max-concurrent` says otherwise, a quarter of the per-route connections of the writes' pool (at least 2), or the whole pool of a `query-client`.

## Event search

The event search Sink (`ElasticsearchEventSinks`, `spi.sink.Sink`) writes each commit as one document of `[<prefix>_]wow_events__<context>__<aggregate>`: the `events` model's record, its system fields under their names and its events as the `nested` array `events`, each `{name, revision, body}` as committed. A document's id is the commit's identity (tenant, aggregate id, version), so a commit written again changes nothing. Each Sink runs as a sink subscription, `elasticsearch-sink.<aggregate>`, from `PublishedSinks`; the Spring Boot host does not start them by itself yet. The body fields are mapped from the Manifest's event schemas when an aggregate's `events` schema is first loaded; a field two events declare with different types is not mapped.

An aggregate whose `query.events.store` names the Elasticsearch store has its `events` queries served from that index: event conditions are `nested` queries, `expand("events")` aggregations are `nested` aggregations, and a query that names no aggregate runs on the index rather than scanning the log. The index shows what the Sink wrote: a commit is searchable once the Sink's subscription has written it and the index refreshed (1 s by default).

## Coming from 9.x

- The 9.x Elasticsearch event store (`wow-elasticsearch` 9.x as `wow.eventsourcing.store.storage=elasticsearch`) is **not a migration source**: `migrate-data` reads a 9.x MongoDB event store only. Move the event streams to the 9.x MongoDB store first, then migrate them into the log ([Migrate the data](https://wow.ahoo.me/migrate/data)).
- The 9.x indices are not read: neither the 9.x snapshot indices nor the 9.x event stream indices have 10.x's layout (each 10.x index records its layout in `_meta` and refuses another). Give 10.x its own `index-prefix` or cluster; once the log is migrated, the snapshots are written from it, and the event search Sink, started from the beginning of the log, fills the events index with every commit.
- 9.x event search through the event store's own index becomes the `events` query model routed to this store; its records are commits (one per version), and per-event questions use `expand("events")`.
