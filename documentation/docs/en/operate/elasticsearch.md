---
title: Elasticsearch
description: Keep a service's snapshots on Elasticsearch, as a copy for their queries or as an aggregate's only snapshot store, and search its events through a sink, while its log stays on PostgreSQL or MongoDB.
---

# Elasticsearch

Elasticsearch never holds a Wow log: every aggregate's log stays on PostgreSQL or MongoDB, the source of truth. Elasticsearch gets the snapshots, so their queries run on a search engine: a copy beside the aggregate's own snapshot store, or that store itself ([below](#only-snapshot-store)). It also gets the event streams, through a sink, so the `events` model can be searched across aggregates. Both can be rebuilt from the log at any time. The module is `wow-elasticsearch`.

## A store of type `elasticsearch`

A store of type `elasticsearch` opens over the application's low-level `Rest5Client`, which Spring Boot's Elasticsearch support configures. Name it beside the log's store and point an aggregate's queries at it. The store then keeps a copy of the aggregate's snapshots for its snapshot queries:

<!-- sample: fragment -->

```yaml
wow:
  stores:
    main: { type: postgres }
    search:
      type: elasticsearch
      index-prefix: shop
  log:
    store: main
  aggregates:
    "[shop.order]":
      query:
        snapshot:
          store: search
        events:
          store: search
```

| Key | Sets | Default |
| --- | --- | --- |
| `wow.stores.<name>.client` | The `Rest5Client` bean the store writes through | The only `Rest5Client` |
| `wow.stores.<name>.query-client` | A second `Rest5Client`, a connection pool of the queries' own | The store's `client` |
| `wow.stores.<name>.index-prefix` | A prefix for every index name: `shop_wow_snapshot__…`, `shop_wow_events__…` | None |
| `wow.stores.<name>.refresh` | When a snapshot write becomes searchable: `none` (within the index's refresh interval), `wait-for` (when its write returns) or `immediate` | `none` |
| `wow.stores.<name>.ddl` | Whether it creates its indices and maps the state's and the events' body fields | `wow.ddl` |
| `wow.stores.<name>.reindex-requests-per-second` | How fast an index's earlier documents are indexed again for a field mapped later ([below](#field-mapped-later)); `-1` for no limit | `1000` |
| `wow.stores.<name>.operation-timeout` | How long the caller of each of its requests waits for an answer ([below](#a-server-that-stops-answering)) | `wow.elasticsearch.operation-timeout`, `30s` |
| `wow.aggregates.[<context>.<aggregate>].snapshot.store` | The store of an aggregate's snapshots, which its commands load from and its state reads read; Elasticsearch too ([below](#only-snapshot-store)) | `wow.snapshot.store`, else the log's store |
| `wow.aggregates.[<context>.<aggregate>].query.snapshot.store` | The store its snapshot queries read; another store than its own then keeps a copy of its snapshots for them | Its own snapshot store |
| `wow.aggregates.[<context>.<aggregate>].query.events.store` | The store its `events` queries read | The log's store |

Each index records its layout in its `_meta` and refuses a store that expects another.

### A server that stops answering {#a-server-that-stops-answering}

Each request of the store, a write, a load or a query, has its caller wait at most `operation-timeout` for an answer, as a MongoDB or PostgreSQL store's calls do: past it the caller fails with a transient error. A command whose load fails replays its log, and a snapshot subscription whose write fails pauses its partition and retries.

The bound is the caller's, not the connection's. The store cancels no request: HttpClient 5 keeps a reused connection whose exchange is cancelled, and it re-runs a request cancelled before its bytes are written on a new connection that nothing then releases. The request runs on in the application's `Rest5Client`, and its connection is freed when the server answers or resets it, or at the client's own response timeout. While a server is paused, the client's pool can fill: later requests wait for a connection while their callers still fail at the bound, and the connections come back once the server answers again.

The client decides how long a connection is held:

- **Spring Boot's client** (`spring.elasticsearch.*`) has no response timeout. `spring.elasticsearch.socket-timeout` (30 seconds by default) does not end an exchange on a connection the pool reuses, so a paused server holds its connections until it answers. A `Rest5ClientBuilderCustomizer` bean sets the response timeout:

<!-- sample: fragment -->

```kotlin
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.elasticsearch.autoconfigure.Rest5ClientBuilderCustomizer
import org.springframework.context.annotation.Bean

@Bean
fun elasticsearchResponseTimeout(): Rest5ClientBuilderCustomizer = object : Rest5ClientBuilderCustomizer {
    override fun customize(builder: Rest5ClientBuilder) = Unit

    override fun customize(builder: RequestConfig.Builder) {
        builder.setResponseTimeout(Timeout.ofSeconds(30))
    }
}
```

- **A client built by hand** sets it with `setRequestConfigCallback { it.setResponseTimeout(Timeout.ofSeconds(30)) }` on its `Rest5Client.builder`.

A request with no answer within the client's response timeout fails and its connection is closed, and it is not re-run. The response timeout bounds every request of the client, so it is set above the longest one the store makes, such as a search or the event index's access stamp.

## Snapshot queries

An aggregate whose `query.snapshot.store` names the Elasticsearch store has its snapshot queries served from there, with the same [query language](../reference/query-language) and capability descriptor as any backend. The queries of a store run under its query bulkhead: `wow.stores.<name>.query.max-concurrent`, by default a quarter of the write pool's connections per route, at least two, or the whole pool of a `query-client`. A query beyond it waits briefly, then is answered 429 `wow.query.busy`.

### Reading your own write {#reading-your-own-write}

A command's `snapshot` wait never covers the copy: it ends once the aggregate's own snapshot store has the commit. Its `queryable` wait ends once the copy's subscription, `wow.snapshot:<store>`, has written it ([Waiting](../concepts/waits)). With the default `refresh: none`, a search sees the commit within the index's refresh interval after that write, so a page that submits a command and then reloads a list can still miss it.

A page that reloads a list after it submits waits `queryable` on a store with `refresh: wait-for`. Each write to the copy then returns once a search sees it, so the list the page reloads holds the commit. That costs every command the page waits for: the wait ends up to a refresh interval later. It also caps the copy's subscription at one batch per write lane a refresh interval: four lanes of at most `wow.snapshot.write-batch` snapshots, 256 by default, so about a thousand aggregates a second at Elasticsearch's default interval of a second. Beyond that, the copy falls behind.

The alternative is to leave the type's queries on its own store, with no copy. Its lists then read the database the commands write, `queryable` and `snapshot` are one wait, and nothing waits for a refresh. Choose the copy for the queries a search engine serves better than the database: full-text search, aggregations over a large population.

A map in the state or in an event body (an object of any keys, such as a `Map<String, Item>` property) is mapped as one `flattened` field, so a value under one of its keys takes presence and exact-match filters only: no range, no relative time, no sort and no aggregation. The descriptor's `dynamic` patterns list exactly those operators.

A snapshot's document, and an event search document, also hold `accessPresent`: `true` under each access attribute the aggregate holds, a single one with a value, a multiple one empty or not. Elasticsearch indexes nothing for an empty array, so an access policy reads there whether an attribute is present, and grants on Elasticsearch the aggregates it grants on any store and to a state read. A document written before an attribute was declared lacks it, as the attribute is missing from it, until the aggregate's next commit or the access walk writes it again.

## Full-text search {#full-text-search}

A string field of the state or of an event body is mapped as a `keyword`: matched by its whole value, as equality, `contains`, `startsWith`, sorts and groups match it. It is searched by words only where its schema marks it as prose: `@QueryText` on the property ([Describe what types cannot](../guide/queries#describe-what-types-cannot)), `x-wow-text` in the Manifest, `text()` in a declaration in code. A marked field gets a `text` sub-field, analyzed by the index's default analyzer, and the capability descriptor lists it among the fields a `search` may name: `record.search.fields`, or its element's.

- **A `search` that names fields** reads those, each of which must be marked; another is answered `unsupported-capability`.
- **A `search` that names no field** reads the marked fields of the record, outside its elements, the ones `record.search.fields` lists. Where none is marked, it is answered `model-search-unsupported`.
- **A phrase** needs every field it reads to take one, as every `text` sub-field does.

Mark only prose a reader searches by words, such as a name, a description, a note or a reason. Ids, enums, codes and references are matched by their whole value, and an index of each of their words only costs space and indexing time.

Marking a field later adds the `text` sub-field to the index and indexes the documents written before it again ([below](#field-mapped-later)). Removing a marker leaves the sub-field in the mapping, and the field is no longer searched by words. When an index name covers several indices, an alias over rollover indices, and only some of them map the sub-field, the field keeps its whole-value matches and is searched by words once every index maps it.

## A field mapped later {#field-mapped-later}

The state's and the events' body fields are mapped when a query first loads the aggregate's schema (under `ddl: auto`), so a field added to a schema later is mapped on an index that already holds documents. Those documents are indexed again in the background, by an `_update_by_query` task that keeps each document as it is, so that they are found by the new field too. Until the task ends, a query that filters, sorts or groups on the new field misses the documents written before the field was mapped: its results are incomplete.

The task runs at `reindex-requests-per-second` documents a second, 1,000 by default, in as many slices as the index has shards. At the default a million documents take about 17 minutes, and ten million about 3 hours. Lower it for a cluster under load; raise it, or set `-1` for no limit, when the field is needed sooner.

The store logs the task's id as it starts, `Elasticsearch index [<index>] mapped [<fields>]: … task [<node>:<id>]`. It does not log the task's end, which only polling the task would tell. Follow the task, and change its rate while it runs, with Elasticsearch's own APIs:

```bash
curl "$ELASTICSEARCH_URL/_tasks/<node>:<id>"
curl -X POST "$ELASTICSEARCH_URL/_update_by_query/<node>:<id>/_rethrottle?requests_per_second=5000"
```

`GET _tasks/<id>` answers `"completed": true` once every earlier document is indexed again.

## Event search

The event search sink writes each commit as one document of `[<prefix>_]wow_events__<context>__<aggregate>`: the `events` model's record, its system fields, and its events as the nested array `events`, each with its name, revision and body as committed. A document's id is the commit's identity, tenant, aggregate id and version, so a commit written again changes nothing. The body fields are mapped from the Manifest's event schemas; a field two events declare with different types is left unmapped.

An aggregate whose `query.events.store` names the Elasticsearch store has its `events` queries served from that index: an event condition is a nested query, an `expand("events")` aggregation a nested aggregation, and a query that names no aggregate searches the index instead of scanning the log. The index shows what the sink has written, a commit becoming searchable once the sink has written it and the index refreshed, one second by default.

Each sink runs as a subscription named `wow.elasticsearch-sink.<aggregate>`, so it resumes from its checkpoint and can start again from the beginning of the log. The host starts the sinks by itself, with no setting of their own: for each Elasticsearch store that an aggregate's `query.events.store` names, it writes exactly those aggregates' streams into that store, whether or not the service publishes them.

## As an aggregate's only snapshot store {#only-snapshot-store}

An aggregate type's own snapshot store may be the Elasticsearch store, its log staying on MongoDB or PostgreSQL. Its state is then stored once, in the index its snapshot queries read:

<!-- sample: fragment -->

```yaml
wow:
  stores:
    main: { type: mongo }
    search: { type: elasticsearch }
  log:
    store: main
  aggregates:
    "[shop.order]":
      snapshot:
        store: search
```

- **Commands load from it by id.** A command loads its state from the index with a real-time get and reads the log's tail from the log's store. The state reads (`GET …/state`) read the index the same way. A write that returned is read back by id at once, whatever `refresh` is.
- **Its default wait ends at Elasticsearch.** `snapshot` ends once the index holds the commit. The type's queries read the same index, so `queryable` is the same wait. With the default `refresh: none`, a search sees the commit within the index's refresh interval after that. A page that submits a command and then reloads a list can miss it, while a read by id cannot: a product created and then listed at once can be missing from the list while its state read by id already has it.
- **`refresh: wait-for` closes that gap for every command of the store's types.** Every write then returns once a search sees it, so every command that waits by default answers up to a refresh interval later. The store's subscription is capped at one batch per write lane a refresh interval, as for a copy ([above](#reading-your-own-write)). Beyond the cap, it falls behind, and a command that misses the state cache replays a longer tail of the log.
- **An outage takes the node out of service.** A type's own snapshot store is on the commands' path, whatever its type. Once it has kept failing for `wow.health.stores.down-after`, the node is not ready. A command that still reaches the node commits, its load replaying the log, but its default wait times out (`504 wow.wait.timeout`, the result committed). A load waits at most `wow.snapshot.load-timeout` (2 seconds by default) for its snapshot: a store that does not answer at all, paused or behind a lost connection, costs each command that long and a replay, and the command gives its slot of the node's bound back then. The load's request runs on in the client until the server answers or the client's own response timeout ends it ([A server that stops answering](#a-server-that-stops-answering)). A state read answers `503 wow.storage.unavailable`. Each load that fails is counted in `wow.store.fallbacks` (`snapshot-load`, reason `failed`, or `timed-out` for one given up at the timeout), and only the first is logged as a warning. The store's subscription pauses, and it catches up once Elasticsearch is back. A type whose commands must not depend on Elasticsearch keeps its snapshots on the log's database with a copy on Elasticsearch, whose outage only stops search ([Choose the Stores](./choosing-storage#elasticsearch-is-never-the-log)).

- **A state the index refuses parks its aggregate.** The state is mapped without dynamic fields, so a new field or a value of another type is kept unindexed. A document the index still refuses parks the aggregate at once in the store's subscription, as every store does with a snapshot it can never take ([Parked aggregates](./replay#parked-aggregates)). For Elasticsearch, that is an object where the mapping holds a value or the reverse, or more access attributes than the index's field limit; for MongoDB, a state beyond its 16 MiB document limit or one BSON cannot hold; for PostgreSQL, a value it refuses as data. Since Elasticsearch is the aggregate's only snapshot store, the aggregate then has no newer snapshot:
  - its commands still commit, and their default waits end failed (`wow.subscription.parked`);
  - a command that misses the state cache replays its log from the last snapshot written, else from the start;
  - its state reads answer that snapshot, else the log replayed, and the lists show that snapshot or nothing.

  Fix the state or the mapping, then retry the parked entries.
- **A PostgreSQL log loses its fast paths.** Only when a type's log, snapshots and checkpoints are on one PostgreSQL store does a command read the snapshot with the log's tail in one round trip. Only then do the snapshot subscription's writes commit without waiting for their own WAL flush, since the checkpoints saved after them cover them. A MongoDB log has neither, so it loses nothing.
## Store combinations

Each aggregate type picks its stores: its log on PostgreSQL or MongoDB, its snapshots there or on Elasticsearch (`wow.log.store`, `wow.snapshot.store`, and the aggregate's `log.store` and `snapshot.store`), a copy of its snapshots and its events search on Elasticsearch, as the sample above sets them. Two consequences:

- **Commands and state reads stay on the aggregate's own store.** A command loads its state from the aggregate's snapshot store and the log after it, and the state reads (`GET …/state`) read that store by id, so neither waits for Elasticsearch's refresh. Each snapshot store is written by a subscription of its own from the log, whether it keeps a type's own snapshots or a copy: `wow.snapshot` for the default snapshot store (`wow.snapshot.store`, else the log's store) and `wow.snapshot:<store>` for each other. It keeps the highest version it was given, so the copy only lags and can be rebuilt. The host refuses Elasticsearch only as a log store, naming the key.
- **Events queries follow the sink.** An aggregate's `events` queries read Elasticsearch once its `query.events.store` names the store, so they see what the sink has written: a commit is searchable shortly after it commits, not at once. Its snapshots may stay on the log's store; the two settings are independent.

## Rebuild

Both copies come from the log. A snapshot index is rebuilt by rebuilding the aggregates' snapshots ([Rebuild and Replay](./replay)). The events index is filled again by starting its sink subscription from the beginning of the log. Neither needs a backup of its own ([Backup and Recovery](./backup)).

## From 9.x

The 9.x Elasticsearch event store is not a migration source: `migrate-data` reads a 9.x MongoDB event store only, so move the streams to MongoDB within 9.x first ([Migrate the Data](../migrate/data)). 9.x indices are not read either. Give Wow 10 its own `index-prefix` or cluster: once the log is migrated, the snapshots are written from it, and the event search sink, started from the beginning, fills the events index.
