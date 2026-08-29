---
title: Query API Client
description: Use wow-apiclient reactive, synchronous, typed, and separate snapshot-aggregation query interfaces.
---

# Query API Client

## Scope

`wow-apiclient.query` provides CoApi transport interfaces for remotely calling Snapshot HTTP query contracts. See the [API Client extension](../extensions/apiclient.md) for dependency installation, `@EnableCoApi`, service discovery, command clients, and general error types.

These clients are snapshot-only. They do not read the runtime Query Model Schema, validate fields on the client, perform authorization, or replace the server-side QueryGateway and HTTP guards. The running server's OpenAPI remains the source of truth for routes and wire contracts.

## Interface Matrix

| Interface | Capability or result | Path relative to `@HttpExchange` |
|---|---|---|
| `SnapshotSingleQueryApi` | typed, dynamic, and state-only single contracts | `snapshot/single`, `snapshot/single/state` |
| `SnapshotListQueryApi` | typed, dynamic, and state-only list contracts | `snapshot/list`, `snapshot/list/state` |
| `SnapshotCursorQueryApi` | typed, dynamic, and state-only cursor contracts | `snapshot/cursor`, `snapshot/cursor/state` |
| `SnapshotPagedQueryApi` | typed, dynamic, and state-only paged contracts | `snapshot/paged`, `snapshot/paged/state` |
| `SnapshotCountQueryApi` | exact count from a `FilterExpression` | `snapshot/count` |
| `SnapshotAggregationQueryApi` | dynamic rows from an `AggregationQuery` | `snapshot/aggregation` |
| `ReactiveSnapshotQueryApi` | reactive composition of single, list, paged, and count | excludes cursor/aggregation |
| `SynchronousSnapshotQueryApi` | synchronous composition of single, list, paged, and count | excludes cursor/aggregation |
| `ReactiveSnapshotAggregationQueryApi` | `Flux<Map<String, Any?>>` | separate aggregation client |
| `SynchronousSnapshotAggregationQueryApi` | `List<Map<String, Any?>>` | separate aggregation client |

Methods on the first six base interfaces declare the listed paths directly with `@PostExchange`. Their Reactive and Synchronous interfaces reuse those methods through inheritance, and the regular composite interfaces inherit the corresponding specialized interfaces.

The two regular composite interfaces inherit their Reactive or Synchronous single/list/paged/count specializations. Cursor is an explicit opt-in capability: additionally inherit `ReactiveSnapshotCursorQueryApi<S>` or `SynchronousSnapshotCursorQueryApi<S>` when needed. Existing concrete implementations of the composite interfaces do not acquire cursor methods.

## Declaring Typed Clients

Follow the project's existing CoApi declaration pattern and bind each interface to the aggregate route base:

```kotlin
@CoApi(baseUrl = "http://order-service:8080")
@HttpExchange("cart")
interface CartQueryClient :
    ReactiveSnapshotQueryApi<CartState>,
    ReactiveSnapshotCursorQueryApi<CartState>

@CoApi(baseUrl = "http://order-service:8080")
@HttpExchange("cart")
interface CartAggregationClient : ReactiveSnapshotAggregationQueryApi
```

Register both interfaces that CoApi must materialize in `@EnableCoApi(clients = [...])`. When CoApi or application conventions require concrete generic metadata, redeclare methods with concrete return types and `@RequestBody`, as the repository example clients do, but do not repeat the path on every method.

## Single, List, Cursor, Paged, and Count

| Operation | Reactive result | Synchronous result |
|---|---|---|
| single typed / state-only / dynamic | `Mono<MaterializedSnapshot<S>>` / `Mono<S>` / `Mono<Map<String, Any>>` | corresponding nullable values |
| list typed / state-only / dynamic | corresponding `Flux` | corresponding `List` |
| cursor typed / state-only / dynamic | `Mono<CursorPage<...>>` | `CursorPage<...>` |
| paged typed / state-only / dynamic | `Mono<PagedList<...>>` | `PagedList<...>` |
| count | `Mono<Long>` | `Long` |

`ISingleQuery`, `IListQuery`, `ICursorQuery`, and `IPagedQuery` execute through the `query`, `queryState`, and `dynamicQuery` extensions. `FilterExpression.count` executes a count. `getById` and `getStateById` are conveniences that build a single query for an `aggregateId`.

```kotlin
val query = CursorQuery(
    filter = "state.status" eq "PAID",
    sort = listOf(Sort("snapshotTime", Sort.Direction.DESC)),
    size = 20,
)
val typed: Mono<CursorPage<MaterializedSnapshot<CartState>>> = query.query(cartQueryClient)
val states: Mono<CursorPage<CartState>> = query.queryState(cartQueryClient)
val dynamic: Mono<CursorPage<Map<String, Any>>> = query.dynamicQuery(cartQueryClient)
```

For the next request, copy the query and replace only its cursor with the returned `nextCursor`; stop when it is `null`. Callers must keep the same filter and sort. The server does not bind or compare them in the token; it reapplies request-scope/security filters and validates the backend cursor structure. Cursor results have no total, previous cursor, or cross-request snapshot consistency, and the server must have cursor encryption configured.

## Complete Snapshot, State-only, and Dynamic Results

- A typed complete snapshot returns `MaterializedSnapshot<S>`, retaining both `state` and snapshot system metadata.
- A state-only result returns `S`. It changes only the response shape; request filters still use `state.*` paths.
- A dynamic result returns `Map<String, Any>` for projections that change the result shape, without `S`'s compile-time field type.
- Aggregation always returns dynamic `Map<String, Any?>` rows; it has no typed or state-only variant.

## Separate Aggregation Client

`ReactiveSnapshotQueryApi` and `SynchronousSnapshotQueryApi` deliberately exclude cursor and aggregation. A client can explicitly add the cursor interface; aggregation still uses a separately declared `ReactiveSnapshotAggregationQueryApi` or `SynchronousSnapshotAggregationQueryApi`, posting an `AggregationQuery` to `snapshot/aggregation`:

```kotlin
val rows: Flux<Map<String, Any?>> = aggregation {
    terms("state.status", "status")
    count("count")
}.query(cartAggregationClient)
```

Aggregation fields, Element paths, backend capabilities, and cost protection remain server responsibilities; see [Snapshot Aggregation](./snapshot-aggregation.md).

## Reactive and Synchronous

Reactive interfaces use `Mono` and `Flux` for non-blocking call chains. Synchronous interfaces return values, `List`, or `PagedList` directly and block the calling thread. Do not call a synchronous client from a Reactor event loop or Wow core reactive processing path.

Both variants submit the same query DTOs to the same HTTP paths. Only their invocation and return models differ; server query semantics do not.

## 404 and Empty-result Semantics

HTTP single returns 404 when no item matches. The provided `ISingleQuery.query`, `queryState`, and `dynamicQuery` helpers, plus `getById` and `getStateById`, turn that 404 into an empty reactive `Mono` or synchronous `null`. Calling the inherited `single`, `singleState`, or `dynamicSingle` method directly is a raw CoApi transport call and does not pass through those helpers' 404 conversion.

A normal no-match list returns an empty `Flux`/`List`; cursor returns `list = []` with `nextCursor = null`; paged returns a `PagedList` with `total = 0` and `list = []`; count returns `0`. None is a single-query 404. Validation, authorization, rate-limit, timeout, and backend errors continue to propagate.

## Event Stream Client Is Not Currently Supported

`wow-apiclient.query` currently provides Snapshot interfaces only. It has no EventStream data-query or aggregation-query client. Published server-side EventStream HTTP routes do not imply a built-in client; if an application needs one, declare it against the actual OpenAPI and preserve the capability boundaries documented in [Event Stream Queries](./event-stream-query.md).
