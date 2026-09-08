---
title: Query Backend
description: Logical Query and Schema inputs, native compilation, and response ownership.
---

# Query Backend

## QueryBackend contract

`QueryBackend` is the aggregate-bound native execution boundary. The Gateway passes the final logical query and the same Schema captured for that subscription:

```kotlin
fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode>
fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode>
fun paged(query: IPagedQuery, schema: QueryModelSchema): Mono<PagedList<ObjectNode>>
fun cursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>>
fun count(query: FilterExpression, schema: QueryModelSchema): Mono<Long>
fun aggregate(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode>
```

The Backend does not read a Provider, run request policies or whole-query public validation, or mask responses. It compiles Filter, Projection, Sort, and Aggregation from native bindings, checks native parameters and physical scope, then accesses storage. Unknown fields never become physical paths by fallback. Typed materialization belongs to the Gateway.

`QueryFieldSchema.value` holds the logical value definition; `binding(capability).physicalField` is absolute. MongoDB explicitly derives relative paths inside element predicates; Elasticsearch uses absolute paths with nested scope. Projection has a separate binding and selects a node with its descendants. Backend-local wildcard expressions never enter the public Query.

## Factories and routing

`SnapshotQueryBackendFactory.create(namedAggregate)` and `EventStreamQueryBackendFactory.create(namedAggregate)` return `QueryBackendBinding`, pairing a Backend with its `QueryModelSchemaProvider`. Abstract factories cache the complete binding; routing factories forward the pair atomically. Spring selects the route once when creating an aggregate Gateway. Query execution and Schema HTTP endpoints use that same pair.

Applications normally inject `SnapshotQueryGateway<OrderState>` or qualify an `EventStreamQueryGateway` by Bean name. Direct factory access is for trusted diagnostics, contract tests, and storage extensions. It bypasses Gateway preparation, scope, ABAC, Mask, and Observer handling.

A low-level caller must explicitly own those responsibilities. For example, public field validation followed by a raw list operation:

```kotlin
val binding = factory.create(namedAggregate)
val query = ListQuery(MatchAllFilter, limit = 10)
val rows = binding.schemaProvider.schema().flatMapMany { schema ->
    binding.backend.list(validateQuery(query, schema), schema)
}
```

This example's `MatchAllFilter` does not restrict deletion state. The Backend, `FilterNormalizer`, and compiler do not append `ACTIVE`. A low-level Snapshot caller that needs active records must explicitly use `DeletionFilter(DeletionState.ACTIVE)`. This example supplies neither authorization nor masking and does not replace an application Gateway.

## Native numeric semantics

Numeric comparisons use the storage precision of their binding; `EXACT_MATCH` does not mean arbitrary-precision source equality. Scalar field metrics retain native aggregation; array/union fields and arithmetic leaves follow the [one numeric contribution per record](./aggregation-query.md#numeric-contributions) contract. The Backend does not scan source to reconstruct array pairing, and runtime output must obey the logical numeric model.

## Node ownership

Every subscription owns fresh mutable `ObjectNode` instances, including retries, repeats, and concurrent subscriptions. Do not share cached nodes or mutate them asynchronously after emission. Normalize MongoDB Documents, Elasticsearch source Maps, BSON, and POJOs to standard JSON trees inside the Backend; reject values that cannot be represented.

## Cursor execution

Before validation, the Gateway appends the unique sort field: `aggregateId` for Snapshot and `id` for EventStream. The Backend does not append it again. Raw callers provide the complete effective sort themselves.

MongoDB uses keyset pagination; Elasticsearch uses search_after without PIT. Both fetch size+1, without count, offset, or total. `CURSOR_SORT` is independent of `SORT`; it requires a bound single value without array ancestry or a Mask-protected source. Backends reject duplicate native sort fields and invalid tokens.

The token is an unsigned, unencrypted Base64URL continuation, not authorization. Return it unchanged. There is no cross-request snapshot, so concurrent writes may affect later pages.

See [Query Model Schema](./query-model-schema.md), [WebFlux](../extensions/webflux.md), and [OpenAPI](../open-api.md) for endpoint and error contracts.
