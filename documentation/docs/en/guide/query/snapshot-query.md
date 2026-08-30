---
title: Snapshot Queries
description: Query an aggregate's current materialized state, snapshot field paths, and published HTTP entries.
---

# Snapshot Queries

## Query Model

`SnapshotQueryGateway<S>` queries `MaterializedSnapshot<S>`: it contains system fields such as `aggregateId`, `tenantId`, `ownerId`, `spaceId`, `version`, event times, and `deleted`, plus the current business state in `state`. A snapshot is for current aggregate state, not the full event history; see [Data Queries](./data-query.md) for shared request shapes.

## Field Paths

Business fields start at `state`, for example `state.status` and `state.total`. The Kotlin DSL's `pathState { ... }` is shorthand for that root path:

```kotlin
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.pathState
import me.ahoo.wow.query.snapshot.query

fun findPaidOrders(queryGateway: SnapshotQueryGateway<OrderState>) = pagedQuery {
    filter {
        pathState { "status" eq "PAID" }
    }
    pagination { index(1); size(20) }
}.query(queryGateway)
```

The equivalent HTTP JSON uses the complete logical path:

```json
{
  "filter": { "op": "EQ", "field": "state.status", "value": "PAID" },
  "pagination": { "index": 1, "size": 20 }
}
```

Whether a field is queryable still depends on the runtime Schema and backend capability; do not write `status` just because a response is state-only.

## Default Deletion Condition

Snapshot queries append `DELETION = ACTIVE` by default, so they do not return deleted snapshots. An explicit `DELETION` in the root expression or its root `AND` conjunction tree overrides that default scope; a deletion condition inside `OR` or `NOR` does not remove the ACTIVE guard. Use the operator explicitly for `DELETED` or `ALL`; see [Filter Expressions](./filter-expression.md#deletion-markers-and-full-text-search).

## JVM Queries

After injecting aggregate-scoped `SnapshotQueryGateway<S>`, extensions execute typed single/list/paged/cursor/count queries; `dynamicQuery` returns `ObjectNode` when projection changes the result shape. After the Backend produces nodes and generic result filters complete, the framework-owned `SchemaMaskQueryFilter` masks from the Query Model Schema, and the Gateway then uses Jackson for typed results. Typed, dynamic, and state-only entries use the same managed path. See [Field Masking](./masking.md) for details, and [Query Backends](./query-backend.md) plus [Query Gateway](./query-gateway.md) for the direct-Factory raw-value boundary.

## Cursor Queries

`CursorQuery` uses `filter`, `projection`, `sort`, `size`, and an optional `cursor`, and returns a `CursorPage` containing only `list` and `nextCursor`. Omit `cursor` or send `null` on the first request. Later requests keep `filter` and `sort` unchanged and send the preceding `nextCursor`; stop when `nextCursor == null`.

The same Snapshot Gateway provides typed, dynamic, and state-only results:

```kotlin
import me.ahoo.wow.query.dsl.cursorQuery
import me.ahoo.wow.query.snapshot.dynamicQuery
import me.ahoo.wow.query.snapshot.pathState
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.query.snapshot.toStateCursorPage

val query = cursorQuery {
    filter { pathState { "status" eq "PAID" } }
    sort { "version".desc() }
    size(20)
}

val typed = query.query(queryGateway)
val dynamic = query.dynamicQuery(queryGateway)
val stateOnly = query.query(queryGateway).toStateCursorPage()
```

The corresponding HTTP request and response are:

```json
{
  "filter": { "op": "EQ", "field": "state.status", "value": "PAID" },
  "projection": { "include": ["state.status", "version"] },
  "sort": [{ "field": "version", "direction": "DESC" }],
  "size": 20,
  "cursor": null
}
```

```json
{
  "list": [],
  "nextCursor": null
}
```

`POST /sales-order/snapshot/cursor` returns complete snapshots, while `POST /sales-order/snapshot/cursor/state` returns state-only values; both return JSON only. See [API Client](./query-api-client.md) for explicit opt-in reactive and synchronous clients, and [Query Backends](./query-backend.md) for execution and token boundaries.

## HTTP Routes

These are the published base snapshot data-query routes for `sales-order`; aggregation and Schema are intentionally not in this table:

```text
POST /sales-order/snapshot/single
POST /sales-order/snapshot/single/state
POST /sales-order/snapshot/list
POST /sales-order/snapshot/list/state
POST /sales-order/snapshot/paged
POST /sales-order/snapshot/paged/state
POST /sales-order/snapshot/cursor
POST /sales-order/snapshot/cursor/state
POST /sales-order/snapshot/count
```

The same single, single/state, list, list/state, paged, paged/state, and count operations are also published with tenant and owner scopes:

```text
POST /tenant/{tenantId}/sales-order/snapshot/{operation}
POST /owner/{ownerId}/sales-order/snapshot/{operation}
```

Here, `{operation}` is one of the nine operations above. List can negotiate JSON or SSE; single, paged, and cursor return JSON. Aggregation and [Query Model Schema (current guidance)](./query-model-schema.md) routes are separate contracts. Generated [OpenAPI](../open-api.md) from the running application is the source of truth for exact paths. An HTTP guard can still limit a DTO that is otherwise valid.

## Complete Snapshot, State-only, and Dynamic Results

- A complete snapshot returns `MaterializedSnapshot<S>` to read state and system metadata together.
- A `state-only` route unwraps only `S`; it changes the response, not `state.*` request fields.
- A dynamic result returns `ObjectNode` for custom projections, without `S`'s compile-time field type.

See [API Client](./query-api-client.md) for reactive and synchronous typed, state-only, and dynamic API-client calls.

## Empty Results and 404

A JVM single query returns an empty `Mono` for no match; list returns an empty `Flux`, while paged and cursor return an empty page. HTTP `snapshot/single` and `snapshot/single/state` return 404 when there is no match. The API Client maps a single-query 404 to an empty reactive `Mono` or synchronous `null`; other errors propagate.

## When to Use Snapshot Queries

Choose snapshot queries for questions such as “what is the order's current status?” or “what is the current balance?”, and for filtering current business state. Choose [Event Stream Queries](./event-stream-query.md) for complete command history, event versions, or event payloads.
