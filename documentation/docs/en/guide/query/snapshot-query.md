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

After injecting aggregate-scoped `SnapshotQueryGateway<S>`, extensions execute typed single/list/paged/count queries; `dynamicQuery` returns `ObjectNode` when projection changes the result shape. The Gateway lets the Backend produce nodes, runs generic result filters, and then uses Jackson for typed results. The current V9 temporarily provides no automatic Mask. See [Query Backends](./query-backend.md) and [Query Gateway](./query-gateway.md) for the direct-Factory bypass boundary.

## HTTP Routes

These are the published base snapshot data-query routes for `sales-order`; aggregation and Schema are intentionally not in this table:

```text
POST /sales-order/snapshot/single
POST /sales-order/snapshot/single/state
POST /sales-order/snapshot/list
POST /sales-order/snapshot/list/state
POST /sales-order/snapshot/paged
POST /sales-order/snapshot/paged/state
POST /sales-order/snapshot/count
```

The same single, single/state, list, list/state, paged, paged/state, and count operations are also published with tenant and owner scopes:

```text
POST /tenant/{tenantId}/sales-order/snapshot/{operation}
POST /owner/{ownerId}/sales-order/snapshot/{operation}
```

Here, `{operation}` is one of the seven operations above. List can negotiate JSON or SSE; single and paged return JSON. Aggregation and [Query Model Schema (current guidance)](./query-model-schema.md) routes are separate contracts. Generated [OpenAPI](../open-api.md) from the running application is the source of truth for exact paths. An HTTP guard can still limit a DTO that is otherwise valid.

## Complete Snapshot, State-only, and Dynamic Results

- A complete snapshot returns `MaterializedSnapshot<S>` to read state and system metadata together.
- A `state-only` route unwraps only `S`; it changes the response, not `state.*` request fields.
- A dynamic result returns `ObjectNode` for custom projections, without `S`'s compile-time field type.

See [API Client](./query-api-client.md) for reactive and synchronous typed, state-only, and dynamic API-client calls.

## Empty Results and 404

A JVM single query returns an empty `Mono` for no match; list returns an empty `Flux`, and paged returns an empty page. HTTP `snapshot/single` and `snapshot/single/state` return 404 when there is no match. The API Client maps a single-query 404 to an empty reactive `Mono` or synchronous `null`; other errors propagate.

## When to Use Snapshot Queries

Choose snapshot queries for questions such as “what is the order's current status?” or “what is the current balance?”, and for filtering current business state. Choose [Event Stream Queries](./event-stream-query.md) for complete command history, event versions, or event payloads.
