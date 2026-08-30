---
title: Query API Client
description: Use wow-apiclient reactive, synchronous, typed, cursor, and separate snapshot-aggregation query interfaces.
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
| `SnapshotPagedQueryApi` | typed, dynamic, and state-only paged contracts | `snapshot/paged`, `snapshot/paged/state` |
| `SnapshotCursorQueryApi` | typed, dynamic, and state-only cursor contracts | `snapshot/cursor`, `snapshot/cursor/state` |
| `SnapshotCountQueryApi` | exact count from a `FilterExpression` | `snapshot/count` |
| `SnapshotAggregationQueryApi` | dynamic rows from an `AggregationQuery` | `snapshot/aggregation` |
| `ReactiveSnapshotQueryApi` | reactive composition of single, list, paged, and count | excludes aggregation |
| `SynchronousSnapshotQueryApi` | synchronous composition of single, list, paged, and count | excludes aggregation |
| `ReactiveSnapshotCursorQueryApi` | `Mono<CursorPage<...>>` | explicit opt-in cursor client |
| `SynchronousSnapshotCursorQueryApi` | `CursorPage<...>` | explicit opt-in cursor client |
| `ReactiveSnapshotAggregationQueryApi` | `Flux<Map<String, Any?>>` | separate aggregation client |
| `SynchronousSnapshotAggregationQueryApi` | `List<Map<String, Any?>>` | separate aggregation client |

Methods on the six base interfaces declare the listed paths directly with `@PostExchange`. Their Reactive and Synchronous interfaces reuse those methods through inheritance.

The regular `ReactiveSnapshotQueryApi` and `SynchronousSnapshotQueryApi` compose only single, list, paged, and count. Neither cursor nor aggregation is inherited by those regular composite interfaces; inherit the corresponding interface explicitly when needed.

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

The regular `ReactiveSnapshotQueryApi` and `SynchronousSnapshotQueryApi` do not inherit cursor interfaces. Add `ReactiveSnapshotCursorQueryApi` as above, or its synchronous counterpart, to opt in explicitly.

## Single, List, Paged, and Count

| Operation | Reactive result | Synchronous result |
|---|---|---|
| single typed / state-only / dynamic | `Mono<MaterializedSnapshot<S>>` / `Mono<S>` / `Mono<Map<String, Any>>` | corresponding nullable values |
| list typed / state-only / dynamic | corresponding `Flux` | corresponding `List` |
| paged typed / state-only / dynamic | `Mono<PagedList<...>>` | `PagedList<...>` |
| count | `Mono<Long>` | `Long` |

`ISingleQuery`, `IListQuery`, and `IPagedQuery` execute through the `query`, `queryState`, and `dynamicQuery` extensions. `FilterExpression.count` executes a count. `getById` and `getStateById` are conveniences that build a single query for an `aggregateId`.

## Cursor Queries

The cursor API accepts `ICursorQuery` directly and returns complete-snapshot, dynamic-map, and state-only `CursorPage` variants:

```kotlin
val request = cursorQuery {
    filter { pathState { "status" eq "PAID" } }
    sort { "version".desc() }
    size(20)
}

val typed = cartQueryClient.cursor(request)
val dynamic = cartQueryClient.dynamicCursor(request)
val stateOnly = cartQueryClient.cursorState(request)
```

The response contains only `list` and `nextCursor`, with no total. A later request keeps the filter and sort and uses the previous token as `cursor`; stop when `nextCursor == null`. The client does not parse the token or restore authorization from it; the server reruns the full Gateway chain on every request.

## Complete Snapshot, State-only, and Dynamic Results

- A typed complete snapshot returns `MaterializedSnapshot<S>`, retaining both `state` and snapshot system metadata.
- A state-only result returns `S`. It changes only the response shape; request filters still use `state.*` paths.
- A dynamic result returns `Map<String, Any>` for projections that change the result shape, without `S`'s compile-time field type.
- Aggregation always returns dynamic `Map<String, Any?>` rows; it has no typed or state-only variant.

## Separate Aggregation Client

`ReactiveSnapshotQueryApi` and `SynchronousSnapshotQueryApi` deliberately exclude aggregation. Declare `ReactiveSnapshotAggregationQueryApi` or `SynchronousSnapshotAggregationQueryApi` separately, then post an `AggregationQuery` to `snapshot/aggregation`:

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

A normal no-match list returns an empty `Flux`/`List`; paged returns a `PagedList` with `total = 0` and `list = []`; cursor returns `list = []` with `nextCursor = null`; count returns `0`. None is a single-query 404. Validation, authorization, rate-limit, timeout, and backend errors continue to propagate.

## Event Stream Client Is Not Currently Supported

`wow-apiclient.query` currently provides Snapshot interfaces only. It has no EventStream data-query or aggregation-query client. Published server-side EventStream HTTP routes do not imply a built-in client; if an application needs one, declare it against the actual OpenAPI and preserve the capability boundaries documented in [Event Stream Queries](./event-stream-query.md).
