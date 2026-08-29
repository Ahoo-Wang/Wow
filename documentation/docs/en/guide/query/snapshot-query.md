---
title: Snapshot Queries
description: Query an aggregate's current materialized state, snapshot field paths, and published HTTP entries.
---

# Snapshot Queries

## Query Model

`SnapshotQueryService<S>` queries `MaterializedSnapshot<S>`: it contains system fields such as `aggregateId`, `tenantId`, `ownerId`, `spaceId`, `version`, event times, and `deleted`, plus the current business state in `state`. A snapshot is for current aggregate state, not the full event history; see [Data Queries](./data-query.md) for shared request shapes.

## Field Paths

Business fields start at `state`, for example `state.status` and `state.total`. The Kotlin DSL's `pathState { ... }` is shorthand for that root path:

```kotlin
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.pathState
import me.ahoo.wow.query.snapshot.query

fun findPaidOrders(queryService: SnapshotQueryService<OrderState>) = pagedQuery {
    filter {
        pathState { "status" eq "PAID" }
    }
    pagination { index(1); size(20) }
}.query(queryService)
```

The equivalent HTTP JSON uses the complete logical path:

```json
{
  "filter": { "op": "EQ", "field": "state.status", "value": "PAID" },
  "pagination": { "index": 1, "size": 20 }
}
```

Whether a field is queryable still depends on the runtime Schema and backend capability; do not write `status` just because a response is state-only.

## Cursor Pagination

Cursor pagination continues from a stable sort and is intended for deep traversal. Omit `cursor` on the first request. On later requests, send the previous page's `nextCursor` unchanged and keep the same `filter` and `sort`:

```json
{
  "filter": { "op": "EQ", "field": "state.status", "value": "PAID" },
  "sort": [{ "field": "snapshotTime", "direction": "DESC" }],
  "size": 20,
  "cursor": null
}
```

```json
{
  "list": [{ "aggregateId": "order-1", "state": { "status": "PAID" } }],
  "nextCursor": "AQ...opaque-encrypted-token..."
}
```

Traversal ends when `nextCursor == null`. `CursorPage` has no `total` or previous cursor. Callers must keep filter and sort unchanged: the token contains no fingerprint for either, so the server does not verify that they are bound to the previous page. It does reapply request-scope and security filters and validate the backend cursor structure. Both user sort and the effective sort after the unique-key append are limited to 32 fields.

Built-in MongoDB and Elasticsearch cursors use JDK AES-256-GCM and require one Base64URL-encoded 32-byte key at `wow.query.cursor.encryption-key`. Without it, the application and existing queries still start and run, but CursorQuery returns `UnsupportedOperationException` from its first page. Rotating the single key invalidates every outstanding cursor. Never put a real key in documentation, source, or logs; see [Infrastructure Configuration](../../reference/config/infrastructure.md) for a placeholder-only example.

The cursor does not create a PIT and provides no snapshot consistency across requests; concurrent writes are observed through the backend's current sorted view. Cursor queries cannot compensate for a missing index or an expensive filter. MongoDB needs a compound index matching the filter and sort, while Elasticsearch uses `search_after`.

## Default Deletion Condition

Snapshot queries append `DELETION = ACTIVE` by default, so they do not return deleted snapshots. An explicit `DELETION` in the root expression or its root `AND` conjunction tree overrides that default scope; a deletion condition inside `OR` or `NOR` does not remove the ACTIVE guard. Use the operator explicitly for `DELETED` or `ALL`; see [Filter Expressions](./filter-expression.md#deletion-markers-and-full-text-search).

## JVM Queries

After injecting aggregate-scoped `SnapshotQueryService<S>`, extensions execute typed single/list/cursor/paged/count queries; `dynamicQuery` returns `DynamicDocument` when projection changes the result shape. See [Query Backends](./query-backend.md) and [Query Gateway](./query-gateway.md) for the Spring `QueryGateway` policy boundary and direct-Factory bypass condition.

## HTTP Routes

These are the published base snapshot data-query routes for `sales-order`; aggregation and Schema are intentionally not in this table:

```text
POST /sales-order/snapshot/single
POST /sales-order/snapshot/single/state
POST /sales-order/snapshot/list
POST /sales-order/snapshot/list/state
POST /sales-order/snapshot/cursor
POST /sales-order/snapshot/cursor/state
POST /sales-order/snapshot/paged
POST /sales-order/snapshot/paged/state
POST /sales-order/snapshot/count
```

The same single, single/state, list, list/state, cursor, cursor/state, paged, paged/state, and count operations are also published with tenant and owner scopes:

```text
POST /tenant/{tenantId}/sales-order/snapshot/{operation}
POST /owner/{ownerId}/sales-order/snapshot/{operation}
```

Here, `{operation}` is one of the nine operations above. List can negotiate JSON or SSE; single, cursor, and paged are JSON-only. Aggregation and [Query Model Schema (current guidance)](./query-model-schema.md) routes are separate contracts. Generated [OpenAPI](../open-api.md) from the running application is the source of truth for exact paths. An HTTP guard can still limit a DTO that is otherwise valid.

## Complete Snapshot, State-only, and Dynamic Results

- A complete snapshot returns `MaterializedSnapshot<S>` to read state and system metadata together.
- A `state-only` route unwraps only `S`; it changes the response, not `state.*` request fields.
- A dynamic result returns `DynamicDocument` for custom projections, without `S`'s compile-time field type.

See [API Client](./query-api-client.md) for reactive and synchronous typed, state-only, and dynamic API-client calls.

## Empty Results and 404

A JVM single query returns an empty `Mono` for no match; list returns an empty `Flux`, and paged returns an empty page. With an encryption key configured, cursor returns `list = []` with `nextCursor = null`; an absent key means cursor is unsupported, not an empty result. HTTP `snapshot/single` and `snapshot/single/state` return 404 when there is no match. The API Client maps a single-query 404 to an empty reactive `Mono` or synchronous `null`; other errors propagate.

## When to Use Snapshot Queries

Choose snapshot queries for questions such as “what is the order's current status?” or “what is the current balance?”, and for filtering current business state. Choose [Event Stream Queries](./event-stream-query.md) for complete command history, event versions, or event payloads.
