---
title: Elasticsearch
description: Use Elasticsearch for event streams, snapshots, and backend-aware queries.
---

# Elasticsearch

`wow-elasticsearch` implements Elasticsearch-backed `EventStore`, `SnapshotStore`, and event-stream/snapshot query backends. Use it when Elasticsearch is already operated and the read side needs full text, aggregations, or large cursor scans. Do not add a search cluster only for event persistence.

## Architecture Overview

Wow owns index names, templates, document shape, version guards, query schema, and storage bindings. Elasticsearch owns mappings, analyzers, shards, replicas, refresh, PIT, `search_after`, and bulk execution. Classpath presence is not wiring; event or snapshot storage must select `elasticsearch`.

## Installation

Direct dependencies:

```kotlin
implementation("me.ahoo.wow:wow-elasticsearch")
implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
```

Starter capability:

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:elasticsearch-support") }
}
```

## Configuration

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200

wow:
  eventsourcing:
    store:
      storage: elasticsearch
    snapshot:
      storage: elasticsearch
```

Defaults are `wow.elasticsearch.enabled=true`, `auto-init-template=true`, `query.batch-size=10000`, and `query.keep-alive=1m`; `compatibility-version` is unset. Event/snapshot batching is disabled. Enabled defaults are `max-size=128`, `max-delay=1ms`, `max-pending-*=4096`, and `lane-count=1`.

### Spring Data Elasticsearch Configuration

`spring.elasticsearch.*` owns connection, authentication, TLS, and client timeouts. Wow uses Spring's reactive client and operations rather than duplicating those properties.

### Wow Configuration

`query.batch-size` must be in `1..10000`, and `keep-alive` must be at least `1ms`. Set `compatibility-version` only when the deployment requires REST compatibility headers, then verify the value against the target cluster.

## Write Batching

EventStore batching uses Bulk `create`. Both direct and batched SnapshotStore writes atomically guard on `_source.version`, so an older snapshot cannot overwrite a newer one. Enable batching only from throughput evidence; queue bounds, shutdown draining, and partial bulk failures become new runtime boundaries.

## Index Naming Rules

Default event indexes are `wow.${contextAlias}.${aggregateName}.es`; snapshot indexes are `wow.${contextAlias}.${aggregateName}.snapshot`. Names participate in storage and query routing, so renaming is a data migration.

## Snapshot Query Field Resolution

The storage adapter (`ElasticsearchQuerySchemaAdapter`, a `QueryStorageAdapter`) reads the target mappings and reports them as storage facts: the physical paths bound for exact match, range, sorting, presence, and projection. `QuerySchemaCatalog` compiles those facts with the logical model into the `QueryModelSchema`; admission resolves each field reference against those bindings, and the compilers consume the resulting `ResolvedField`s. Multi-fields, runtime fields, and disabled objects follow Elasticsearch mappings; do not guess `.keyword` in the HTTP layer.

### Keywords with `ignore_above` {#keyword-ignore-above}

A `keyword` whose `ignore_above` is 8191 or more counts as indexing every value and has the operators of an uncapped keyword: exact match, `in`/`notIn`, prefix and contains, range, sorting and terms aggregation. Wow's default templates give `tags.*` (the ABAC tags), `id`, `*Id` and the event stream's dynamic strings exactly this cap, the most characters that fit Lucene's 32766-byte term limit; without it, a longer value would fail the whole document write. The limit: a value longer than `ignore_above` is kept in `_source` but not indexed, so no filter matches it (`eq`, `in`, prefix or contains with that value find nothing; `ne` and `notIn` include the document), it sorts as missing and it falls into no terms bucket. Presence filters (`exists`, `isNull`, `isEmpty`) still see the value through Elasticsearch's `_ignored` field, so a resource whose ABAC tag is over-long never reads as untagged, that is public; inside a `nested` element such a value reads as missing. `_ignored` also names a field whose value `ignore_malformed` dropped (a number or date that did not parse), so `isNull`, `notExists` and `isEmpty` treat that value as present as well. A `flattened` field records nothing in `_ignored`, so one with `ignore_above` offers no presence filter.

A smaller `ignore_above`, such as the `text` + `keyword` (`ignore_above: 256`) Elasticsearch infers for an unmapped string, can drop values a query would need, so the field has no query operators; a string enum whose every declared value fits the cap is the exception. Map such fields explicitly (see [String field aggregability](../query/aggregation-query.md#es-string-aggregability)). Wow 9.1.x refuses every keyword with `ignore_above` apart from such enums.

## Before the First Write

Wow creates an aggregate's index on its first write, so a new aggregate has no index until then. Queries of a missing index answer nothing: lists, pages and cursors are empty, counts are `0`, and an aggregation without groups answers its empty summary, as a snapshot load of a missing index does. The query schema of a missing index is compiled from the mapping the index will be created with: the matching index templates, as Elasticsearch simulates them for the index name (`POST _index_template/_simulate_index/<index>`), or no field when no template matches. That needs the `manage_index_templates` cluster privilege, which `auto-init-template` needs as well; without it the schema stays unavailable (`QuerySchemaUnavailable`, HTTP 503) until the index exists.

A field the simulated mapping does not map yet (one dynamic mapping will add, or an ABAC tag key) is admitted with every operator its logical type allows, so a filter, sort or group-by on it answers nothing rather than being refused; a field the templates map keeps what its mapping proves. This provisional schema is never published: it is kept for 2 seconds (`DefaultQueryModelSchemaProvider.DEFAULT_PROVISIONAL_TTL`), so an aggregate that is never written does not simulate its templates on every query, and the first query after that compiles it again. Within 2 seconds of the first write the index's own mapping is compiled and published, with the fields its first documents added, without waiting for a revalidation. An aggregate with an index definition (below) has its index from startup and never reaches this path.

## Revalidate the Runtime Query Schema

After mappings change, the runtime schema must be resolved again. Each instance revalidates its query schemas every `wow.query.schema.revalidate-interval`; to pick up a change now, call the `wowQuerySchema` actuator endpoint on every instance. Revalidation updates the in-memory schema only; it does not backfill documents or change mappings.

## Configure Event Stream Index Template

With `auto-init-template=true`, `IndexTemplateInitializer` verifies the event template. Request failure, empty response, or missing acknowledgment fails storage wiring. If the platform owns templates, disable initialization only with versioned template and deployment evidence.

## Configure Snapshot Index Template

The snapshot template defines system fields and the dynamic-state baseline. A template affects new indexes or later mapping behavior; it does not repair an existing index.

The generic snapshot template is the fallback for storage-only snapshots. Queryable snapshots should provide a concrete index definition with business mappings at `META-INF/wow/elasticsearch/wow.sales.order.snapshot.json` or `config/wow/elasticsearch/wow.sales.order.snapshot.json`:

```json
{
  "mappings": {
    "properties": {
      "state": {
        "properties": {
          "status": { "type": "keyword" }
        }
      }
    }
  }
}
```

The resource key is the final index name computed by Wow. The working-directory file replaces classpath files; without a working file, duplicate classpath files fail startup. Missing resources keep the generic-template behavior. Existing indexes are skipped, so mapping changes require explicit reindex or migration. Resource JSON follows Elasticsearch client and cluster validation semantics. Resource presence requests creation regardless of storage-routing configuration.

## Full-Text Search

Full text comes from a target field's text mapping and analyzer. `wow-elasticsearch` does not promise it for every string.

### Add Full-Text Index for State Fields

Declare analyzers and text/multi-fields in platform-owned templates without replacing required Wow system fields. Verify mappings for both old and new indexes.

### Execute Full-Text Search

Use a field through the Wow query API only when runtime schema publishes the corresponding capability. Native Elasticsearch DSL is not automatically part of the public Wow request model.

## Aggregation Queries

The Wow aggregation AST compiles to Elasticsearch aggregations. Nested elements, numeric/time types, and missing-value semantics depend on both the public contract and mappings. Verify them with real backend TCK/integration tests.

## Index Design Recommendations

Design indexes from query, write, retention, and recovery objectives. Do not add text/keyword multi-fields to every state field by default.

### Sharding Strategy

Elasticsearch owns shards, replicas, and routing. Validate actual shard size, write concurrency, and query fan-out; Wow does not select a topology.

### Index Lifecycle Management (ILM)

If EventStore is authoritative, deleting events through ILM breaks replay. Configure rollover/delete only when data ownership and recovery explicitly permit it. Snapshot lifecycle must match its rebuild path.

## Performance Optimization

Observe bulk latency/errors, refresh, segments, heap, PIT count, and query latency before changing batch, mappings, or topology.

### Bulk Indexing

Batch options require `max-size>1`, positive `max-delay`, pending capacity no smaller than batch size, and `lane-count>0`. One aggregate remains in one lane. Increase lanes only for a measured concurrency bottleneck.

### Query Optimization

Full scans use PIT plus `search_after`, with configured batch size and keep-alive. Batch size also cannot exceed target `index.max_result_window`. Fix mappings and query shape before blindly increasing it.

An offset page reads `from + size` records, which Elasticsearch caps at the index's `index.max_result_window` (10000 unless the index sets it). A paged query whose `page × size` passes it is rejected before the search as `SIZE_OUT_OF_RANGE` (HTTP 400) on `pagination`; read deeper with a cursor query.

## Troubleshooting

Verified failures include template request/empty/unacknowledged responses, invalid query or batch bounds, bulk item errors, stale-snapshot guards, and mapping/schema conflicts.

### Common Issues

Retain index/alias, resolved mapping, request, item-level response error, and runtime schema as evidence.

#### 1. Query reports an unmapped, incompatible, or ambiguous multi-field

Inspect the actual mapping and runtime schema. Do not hard-code `.keyword` for every field. Correct templates/mappings or the explicit public field contract, then revalidate the schema.

#### 2. The revalidation endpoint is unavailable or revalidation fails

Verify that Spring Boot Actuator is on the classpath and exposes the `wowQuerySchema` endpoint on the management plane, and that the query factory is wired so `QuerySchemaCatalog` has the model to revalidate; the catalog's `wow.query.schema.refresh` metric reports each outcome. Mapping-read failure must remain a failure rather than degrading to “all fields are queryable.”

#### 3. An alias or data stream cannot be resolved

The current converter emits concrete index names. Introducing aliases or data streams requires a migration consistent across reads, writes, and mapping resolution.

#### 4. Old data is still unqueryable after updating a template and revalidating

Templates do not rewrite historical mappings or data. Reindex or migrate explicitly; schema revalidation only rereads current backend capability.

#### 5. A runtime-field query is rejected

Runtime-field projection and some capabilities are deliberately limited by mapping resolution. Follow the runtime schema instead of bypassing public query validation.

## Complete Configuration Example

```yaml
spring:
  elasticsearch:
    uris: ${ELASTICSEARCH_URIS}

wow:
  elasticsearch:
    auto-init-template: true
    query:
      batch-size: 10000
      keep-alive: 1m
    event-store-batch:
      enabled: false
    snapshot-store-batch:
      enabled: false
  eventsourcing:
    store:
      storage: elasticsearch
    snapshot:
      storage: elasticsearch
```

## Best Practices

- Select event/snapshot storage explicitly and inspect resulting bindings.
- Let the platform own mappings, templates, ILM, backups, and reindexing.
- Preserve snapshot version guards and item-level bulk failures.
- Verify mappings, PIT, aggregations, and upgrades on a real cluster.

Focused check:

```bash
./gradlew :wow-elasticsearch:check
```

Next, read [Query](../query.md) and [Infrastructure configuration](../../reference/config/infrastructure.md).
