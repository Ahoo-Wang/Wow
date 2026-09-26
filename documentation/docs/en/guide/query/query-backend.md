---
title: Query Backend
description: Logical Query and Schema inputs, native compilation, and response ownership.
---

# Query Backend

## QueryBackend contract

`QueryBackend` is the aggregate-bound native execution boundary. Every operation receives an `AdmittedQuery`: the final logical query, the Schema captured for that subscription, the query entry and the resolution of every field reference in the query. Only admission creates one, so a query that skipped validation cannot reach a Backend:

```kotlin
val cursorPositions: CursorPositionCodec
fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode>
fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage>
fun count(query: AdmittedQuery<FilterExpression>): Mono<Long>
fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode>
```

The core derives every query shape from these four primitives (`single`, `list`, `paged`, `cursor` and `aggregate` in `BackendQueries`): single is `page(Offset(0, 1, withTotal = false))`, list is `stream`, paged is `page(Offset(offset, size, withTotal = true))`, and cursor is `page(Keyset(after, size + 1))`, whose extra row decides whether a next page exists. `BackendPage` carries the rows, the total when the window asked for it, and each row's native cursor position for a keyset window. Only the core chooses windows.

The Backend does not read a Provider, run request policies or whole-query public validation, look fields up, or mask responses. It compiles Filter, Projection, Sort, and Aggregation from the field resolutions admission registered, checks native parameters, then accesses storage. Unknown fields never become physical paths by fallback. Typed materialization belongs to the Gateway.

Admission rebuilds every node that carries a field as a fresh instance and registers its `ResolvedField` by identity: `admitted.field(reference)` answers a `QueryField` of the admitted query, and `admitted.systemField(filter)` answers a system-field filter such as `TENANT_ID` or `DELETION`. Identity rather than equality is the key, so two equal conditions in different element scopes, or one `QueryField` a caller reused across scopes, resolve separately. A `ResolvedField` carries the absolute logical path, the element ancestors, the physical container of the enclosing element scope (`physicalParent`), the capability the reference was admitted with and the absolute `physicalField` bound for it, with map keys already substituted; `relativePhysicalField` is that field relative to its container. It also carries the narrow facts translation needs from the field's compiled capability record: `responseField`, `cardinality` and `temporal` (how the field stores time; a reference admitted for a date group or date difference always stores a date or an epoch). Backends read these instead of the schema: `AdmittedQuery` exposes the query, its entry, its `model` and the resolutions, not the schema itself. MongoDB uses relative paths inside `$elemMatch` and absolute paths after `$unwind`; Elasticsearch uses absolute paths with nested scope. Projection resolves to its separate binding and selects a node with its descendants. Backend-local wildcard expressions never enter the public Query.

The native compilers accept only admitted queries; there is no entry point that compiles a hand-built filter on physical fields. The event stores do not query through them: `EventStore` operations build their native searches directly from their typed arguments (aggregate id, version or time range) on the fields the event store itself writes, without admission or a Schema.

## Factories and routing

`SnapshotQueryBackendFactory.create(namedAggregate)` and `EventStreamQueryBackendFactory.create(namedAggregate)` return `QueryBackendBinding`, pairing a Backend with its storage adapter (`QueryStorageAdapter`). The adapter only reports native facts: for each logical path, the capabilities its indexes, mappings or validator can execute and where each binds. The core `QuerySchemaCatalog` owns the model sources and the sensitivity policy, merges the logical model, applies the storage-independent rules (a cursor needs one value per record, temporal aggregation needs a date or epoch encoding, an element scope is an array of objects) and publishes one `QueryModelSchemaProvider` per aggregate and model. Gateways, point reads and the Schema HTTP endpoint all read the Catalog. Abstract factories cache the complete binding; routing factories forward it atomically, and Spring selects the route once when creating an aggregate Gateway.

A storage registers its factories through the `QueryBackendProvider` SPI: a `name` and the snapshot and/or event-stream factory it serves. The Spring starter collects every provider bean and routes by name, so a new storage implements its backends, registers a provider, and needs no starter change:

```kotlin
@Bean
fun archiveQueryBackendProvider(factory: ArchiveSnapshotQueryBackendFactory): QueryBackendProvider =
    QueryBackendProvider.snapshot("archive", factory)
```

Built-in storages register under their storage name (`mongo`, `elasticsearch`), which `storage` routes and the default storage resolve to; a route's `binding` names any other provider. Providers may share a name when they serve different read models (MongoDB registers its snapshot and event-stream providers separately, each under its own storage condition); two providers of one name that serve the same read model fail at startup.

Applications normally inject `SnapshotQueryGateway<OrderState>` or qualify an `EventStreamQueryGateway` by Bean name. Direct factory access is for trusted diagnostics, contract tests, and storage extensions. It bypasses Gateway preparation, scope, ABAC, Mask, and Observer handling.

A low-level caller must explicitly own those responsibilities. `QueryAdmission.Trusted` runs only the last admission steps (a cursor's identity tie-breaker, public field validation, normalization and field resolution) without Gateway preparation. For example, a raw list operation:

```kotlin
val backend = factory.create(namedAggregate).backend
val query = ListQuery(MatchAllFilter, limit = 10)
val rows = catalog.schema(namedAggregate, QueryModel.SNAPSHOT).flatMapMany { schema ->
    backend.list(QueryAdmission.Trusted.list(query, schema))
}
```

This example's `MatchAllFilter` does not restrict deletion state. The Backend, `FilterNormalizer`, and compiler do not append `ACTIVE`. A low-level Snapshot caller that needs active records must explicitly use `DeletionFilter(DeletionState.ACTIVE)`. This example supplies neither authorization nor masking and does not replace an application Gateway.

## Native numeric semantics

Numeric comparisons use the storage precision of their binding; `EXACT_MATCH` does not mean arbitrary-precision source equality. Scalar field metrics retain native aggregation; array/union fields and arithmetic leaves follow the [one numeric contribution per record](./aggregation-query.md#numeric-contributions) contract. The Backend does not scan source to reconstruct array pairing, and runtime output must obey the logical numeric model.

## Node ownership

Every subscription owns fresh mutable `ObjectNode` instances, including retries, repeats, and concurrent subscriptions. Do not share cached nodes or mutate them asynchronously after emission. Normalize MongoDB Documents, Elasticsearch source Maps, BSON, and POJOs to standard JSON trees inside the Backend; reject values that cannot be represented.

## Cursor execution

Before validation, admission appends the unique sort field: `aggregateId` for Snapshot and `id` for EventStream. The Backend does not append it again.

MongoDB uses keyset pagination; Elasticsearch uses search_after without PIT. Neither counts, skips or returns a total. `CURSOR_SORT` is independent of `SORT`; it requires a bound single value without array ancestry or a Mask-protected source. Admission rejects cursor sorts whose fields share one physical field.

A cursor position is the storage's own value: the BSON values at the physical sort fields for MongoDB, taken before any field only the cursor needed is stripped, and `hit.sort()` for Elasticsearch. The Backend encodes positions through its `CursorPositionCodec`; the core owns the token around them: a version, a fingerprint of the model (aggregate and read model) with the effective sort's field names and directions, and the payload, in unpadded Base64URL. The next token encodes the position of the page's last row, never a value from the rows, so masking cannot leak into it. A token that does not decode, or that was issued for another model or sort, fails with `Invalid cursor.` before any I/O; the client restarts from the first page. Tokens issued before this format are rejected the same way.

The token is unsigned and unencrypted, not authorization: every page is admitted again and its keyset condition is ANDed with the full admitted filter, so a forged position is no more than a range condition the caller could write. The fingerprint leaves out the filter and the schema version, so a caller that recomputes a time bound per page keeps paging. There is no cross-request snapshot, so concurrent writes may affect later pages.

## Declared storage support

Besides each field's native capabilities, the storage adapter declares in `QueryModelSchema.storage` how the storage pages (keyset pages, unbounded streams) and aggregates (HAVING, top-N by a metric, dense fill, percentile, distinct count), each `NATIVE`, `RESIDUAL` or `NONE`. For a `RESIDUAL` operator the core computes it after the Backend with a shared pure function and adjusts what it sends down: it removes HAVING, the metric sort or the dense flag from the query and asks for every group (`GroupWindow.All`) when the operator needs them all, then applies dense fill, HAVING and top-N or the limit, in that order. A feature declared `NONE` is rejected before any I/O. MongoDB computes all of them natively; Elasticsearch declares HAVING, top-N by a metric and dense fill `RESIDUAL`, since composite aggregations have no bucket selector, no metric ordering and no empty buckets.

See [Query Model Schema](./query-model-schema.md), [WebFlux](../extensions/webflux.md), and [OpenAPI](../open-api.md) for endpoint and error contracts.
