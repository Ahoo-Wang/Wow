---
title: Query Backend
description: Logical Query and Schema inputs, native compilation, and response ownership.
---

# Query Backend

## QueryBackend contract

`QueryBackend` is the aggregate-bound native execution boundary. Every operation receives an `AdmittedQuery`: the final logical query, the Schema captured for that subscription, the query entry and the resolution of every field reference in the query. Only admission creates one, so a query that skipped validation cannot reach a Backend:

```kotlin
fun single(admitted: AdmittedQuery<ISingleQuery>): Mono<ObjectNode>
fun list(admitted: AdmittedQuery<IListQuery>): Flux<ObjectNode>
fun paged(admitted: AdmittedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>>
fun cursor(admitted: AdmittedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>>
fun count(admitted: AdmittedQuery<FilterExpression>): Mono<Long>
fun aggregate(admitted: AdmittedQuery<AggregationQuery>): Flux<ObjectNode>
```

The Backend does not read a Provider, run request policies or whole-query public validation, look fields up, or mask responses. It compiles Filter, Projection, Sort, and Aggregation from the field resolutions admission registered, checks native parameters, then accesses storage. Unknown fields never become physical paths by fallback. Typed materialization belongs to the Gateway.

Admission rebuilds every node that carries a field as a fresh instance and registers its `ResolvedField` by identity: `admitted.field(reference)` answers a `QueryField` of the admitted query, and `admitted.systemField(filter)` answers a system-field filter such as `TENANT_ID` or `DELETION`. Identity rather than equality is the key, so two equal conditions in different element scopes, or one `QueryField` a caller reused across scopes, resolve separately. A `ResolvedField` carries the absolute logical path, the element ancestors, the physical container of the enclosing element scope (`physicalParent`), the capability the reference was admitted with and the absolute `physicalField` bound for it, with map keys already substituted; `relativePhysicalField` is that field relative to its container. MongoDB uses relative paths inside `$elemMatch` and absolute paths after `$unwind`; Elasticsearch uses absolute paths with nested scope. Projection resolves to its separate binding and selects a node with its descendants. Backend-local wildcard expressions never enter the public Query.

## Factories and routing

`SnapshotQueryBackendFactory.create(namedAggregate)` and `EventStreamQueryBackendFactory.create(namedAggregate)` return `QueryBackendBinding`, pairing a Backend with its `QueryModelSchemaProvider`. Abstract factories cache the complete binding; routing factories forward the pair atomically. Spring selects the route once when creating an aggregate Gateway. Query execution and Schema HTTP endpoints use that same pair.

Applications normally inject `SnapshotQueryGateway<OrderState>` or qualify an `EventStreamQueryGateway` by Bean name. Direct factory access is for trusted diagnostics, contract tests, and storage extensions. It bypasses Gateway preparation, scope, ABAC, Mask, and Observer handling.

A low-level caller must explicitly own those responsibilities. `QueryAdmission` runs the last admission steps (a cursor's identity tie-breaker, public field validation, normalization and field resolution) without Gateway preparation. For example, a raw list operation:

```kotlin
val binding = factory.create(namedAggregate)
val query = ListQuery(MatchAllFilter, limit = 10)
val rows = binding.schemaProvider.schema().flatMapMany { schema ->
    binding.backend.list(QueryAdmission.list(query, schema))
}
```

This example's `MatchAllFilter` does not restrict deletion state. The Backend, `FilterNormalizer`, and compiler do not append `ACTIVE`. A low-level Snapshot caller that needs active records must explicitly use `DeletionFilter(DeletionState.ACTIVE)`. This example supplies neither authorization nor masking and does not replace an application Gateway.

## Native numeric semantics

Numeric comparisons use the storage precision of their binding; `EXACT_MATCH` does not mean arbitrary-precision source equality. Scalar field metrics retain native aggregation; array/union fields and arithmetic leaves follow the [one numeric contribution per record](./aggregation-query.md#numeric-contributions) contract. The Backend does not scan source to reconstruct array pairing, and runtime output must obey the logical numeric model.

## Node ownership

Every subscription owns fresh mutable `ObjectNode` instances, including retries, repeats, and concurrent subscriptions. Do not share cached nodes or mutate them asynchronously after emission. Normalize MongoDB Documents, Elasticsearch source Maps, BSON, and POJOs to standard JSON trees inside the Backend; reject values that cannot be represented.

## Cursor execution

Before validation, the Gateway appends the unique sort field: `aggregateId` for Snapshot and `id` for EventStream. The Backend does not append it again. Raw callers provide the complete effective sort themselves.

MongoDB uses keyset pagination; Elasticsearch uses search_after without PIT. Both fetch size+1, without count, offset, or total. `CURSOR_SORT` is independent of `SORT`; it requires a bound single value without array ancestry or a Mask-protected source. Admission rejects cursor sorts whose fields share one physical field; Backends reject invalid tokens.

The token is an unsigned, unencrypted Base64URL continuation, not authorization. Return it unchanged. There is no cross-request snapshot, so concurrent writes may affect later pages.

See [Query Model Schema](./query-model-schema.md), [WebFlux](../extensions/webflux.md), and [OpenAPI](../open-api.md) for endpoint and error contracts.
