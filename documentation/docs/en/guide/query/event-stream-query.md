---
title: Event Stream Queries
description: Query aggregate event history, event-stream field paths, and published HTTP entries.
---

# Event Stream Queries

## Query Model

`EventStreamQueryGateway` queries `DomainEventStream`. An event stream is the event collection produced by one command execution. Its system envelope includes fields such as `id`, `aggregateId`, `tenantId`, `ownerId`, `spaceId`, `version`, `createTime`, `requestId`, and `commandId`; its event collection is `body`. It retains the full history and does not automatically add the snapshot `DELETION = ACTIVE` condition.

## Root Fields and Event Body

Query root fields directly, such as `aggregateId`, `tenantId`, `version`, and `createTime`. `body` is an event array; one event's metadata is `body.id`, `body.name`, `body.revision`, and `body.bodyType`, while its payload is `body.body`. Payload fields must be declared by Query Model Schema and are constrained by MongoDB queryable storage or Elasticsearch `body.body` mapping capability.

## JVM Queries

`EventStreamQueryGateway` supports typed and dynamic single/list/paged/cursor/count queries on the JVM; `dynamicQuery` returns `ObjectNode`. The Gateway also provides JVM aggregation; see [Event Stream Aggregation](./event-stream-aggregation.md) for its JVM and HTTP/OpenAPI contracts and examples.

This example pages through root fields:

```kotlin
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.query

fun findRecentStreams(queryGateway: EventStreamQueryGateway) = pagedQuery {
    filter { tenantId("tenant-a") }
    sort { "createTime".desc() }
    pagination { index(1); size(20) }
}.query(queryGateway)
```

A Spring-managed aggregate Gateway executes the full governance chain. See [Query Backends](./query-backend.md) and [Query Gateway](./query-gateway.md) for the direct Backend Factory bypass boundary.

Use `cursorQuery` for forward-only traversal. Omit the cursor initially, then keep the filter and sort unchanged and send `nextCursor` until it is `null`:

```kotlin
import me.ahoo.wow.query.dsl.cursorQuery
import me.ahoo.wow.query.event.query

fun findRecentStreams(
    queryGateway: EventStreamQueryGateway,
    cursor: String?,
) = cursorQuery {
    filter { tenantId("tenant-a") }
    sort { "createTime".desc() }
    size(20)
    cursor(cursor)
}.query(queryGateway)
```

## HTTP Routes

These are the currently published base event-stream data-query routes for `sales-order`:

```text
POST /sales-order/event/list
POST /sales-order/event/paged
POST /sales-order/event/cursor
POST /sales-order/event/count
```

The same list, paged, cursor, and count operations are also published with tenant and owner scopes:

```text
POST /tenant/{tenantId}/sales-order/event/{list|paged|cursor|count}
POST /owner/{ownerId}/sales-order/event/{list|paged|cursor|count}
```

Aggregation and Schema are contracts separate from the data-query shapes above:

```text
POST /sales-order/event/aggregation
POST /tenant/{tenantId}/sales-order/event/aggregation
POST /owner/{ownerId}/sales-order/event/aggregation
GET /sales-order/event/schema
POST /sales-order/event/schema/refresh
```

The event-stream cursor body is the same shape as Snapshot: `filter`, `projection`, `sort`, `size`, and an optional `cursor`. Its response contains only `list` and `nextCursor`:

```http
POST /sales-order/event/cursor
Content-Type: application/json
Accept: application/json

{
  "filter": { "op": "EQ", "field": "tenantId", "value": "tenant-a" },
  "projection": { "include": ["id", "aggregateId", "createTime"] },
  "sort": [{ "field": "createTime", "direction": "DESC" }],
  "size": 20,
  "cursor": null
}
```

The cursor route returns JSON only; there is no cursor SSE. There is still no event-stream `single` HTTP route and no EventStream API Client. See [Event Stream Aggregation](./event-stream-aggregation.md) for aggregation requests and JSON/SSE responses. The Schema routes are model-level entries without tenant/owner variants. Generated OpenAPI from the running application is the source of truth for exact paths.

## Loading an Event Stream by Version

Load an aggregate ID and contiguous version range through the GET route, for example:

```http
GET /tenant/tenant-a/sales-order/order-1/event/3/8
Accept: application/json
```

It constructs a list query by `aggregateId` and version range. The published `sales-order` path has a tenant prefix; no owner variant is declared. List loading can negotiate JSON or SSE; generated application OpenAPI is the source of truth for other aggregates' scope variants.

## Empty Results

A JVM single query returns an empty `Mono` for no match; list returns an empty `Flux`, while paged and cursor return an empty page. HTTP list, paged, cursor, and version-range loading return an empty collection or page for no match; they have no single-query 404 semantics. HTTP guard, Schema resolution, or authorization failures remain errors and must not be confused with an empty result.

## Differences from Snapshot Queries

| Aspect | Event stream | Snapshot |
| --- | --- | --- |
| Business-data root | `body` event array; payload is `body.body` | `state` current business state |
| Deletion default | No deletion condition | `DELETION = ACTIVE` by default |
| HTTP data queries | list, paged, cursor, count, version-range load | single, list, paged, cursor, count, and state-only |
| HTTP aggregation | `event/aggregation`, JSON or SSE | `snapshot/aggregation`, JSON or SSE |
| HTTP Schema | `event/schema` and refresh | `snapshot/schema` and refresh |
| API Client | None | Separate snapshot contracts exist |

## When to Use Event Stream Queries

Use event-stream queries for complete event history, the events produced by one command, version ranges, or event payloads. Use [Snapshot Queries](./snapshot-query.md) to read or filter current business state.
