---
title: Event Stream Queries
description: Query aggregate event history, event-stream field paths, and published HTTP entries.
---

# Event Stream Queries

## Query Model

`EventStreamQueryService` queries `DomainEventStream`. An event stream is the event collection produced by one command execution. Its system envelope includes fields such as `id`, `aggregateId`, `tenantId`, `ownerId`, `spaceId`, `version`, `createTime`, `requestId`, and `commandId`; its event collection is `body`. It retains the full history and does not automatically add the snapshot `DELETION = ACTIVE` condition.

## Root Fields and Event Body

Query root fields directly, such as `aggregateId`, `tenantId`, `version`, and `createTime`. `body` is an event array; one event's metadata is `body.id`, `body.name`, `body.revision`, and `body.bodyType`, while its payload is `body.body`. Payload fields must be declared by Query Model Schema and are constrained by MongoDB queryable storage or Elasticsearch `body.body` mapping capability.

## JVM Queries

`EventStreamQueryService` supports typed and dynamic single/list/paged/count queries on the JVM; `dynamicQuery` returns `DynamicDocument`. The service interface also has JVM aggregation; see [Event Stream Aggregation](./event-stream-aggregation.md) for its JVM and HTTP/OpenAPI contracts and examples.

This example pages through root fields:

```kotlin
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.query

fun findRecentStreams(queryService: EventStreamQueryService) = pagedQuery {
    filter { tenantId("tenant-a") }
    sort { "createTime".desc() }
    pagination { index(1); size(20) }
}.query(queryService)
```

A Spring-managed service enters QueryGateway; see [Query Backends](./query-backend.md) and [Query Gateway](./query-gateway.md) for the direct-Factory bypass boundary.

## HTTP Routes

These are the currently published event-stream routes for `sales-order`:

```text
POST /sales-order/event/list
POST /sales-order/event/paged
POST /sales-order/event/count
GET /tenant/{tenantId}/sales-order/{id}/event/{headVersion}/{tailVersion}
```

Aggregation and Schema are contracts separate from the data-query shapes above:

```text
POST /sales-order/event/aggregation
POST /tenant/{tenantId}/sales-order/event/aggregation
POST /owner/{ownerId}/sales-order/event/aggregation
GET /sales-order/event/schema
POST /sales-order/event/schema/refresh
```

There is still no event-stream `single` HTTP route and no EventStream API Client. See [Event Stream Aggregation](./event-stream-aggregation.md) for aggregation requests and JSON/SSE responses. The Schema routes are model-level entries without tenant/owner variants.

## Loading an Event Stream by Version

Load an aggregate ID and contiguous version range through the GET route, for example:

```http
GET /tenant/tenant-a/sales-order/order-1/event/3/8
Accept: application/json
```

It constructs a list query by `aggregateId` and version range. The actual `sales-order` route has a tenant path prefix. List loading can negotiate JSON or SSE; generated application OpenAPI is the source of truth for other aggregates' scope variants.

## Empty Results

A JVM single query returns an empty `Mono` for no match; list returns an empty `Flux`, and paged returns an empty page. HTTP list, paged, and version-range loading return an empty collection or page for no match; they have no single-query 404 semantics. HTTP guard, Schema resolution, or authorization failures remain errors and must not be confused with an empty result.

## Differences from Snapshot Queries

| Aspect | Event stream | Snapshot |
| --- | --- | --- |
| Business-data root | `body` event array; payload is `body.body` | `state` current business state |
| Deletion default | No deletion condition | `DELETION = ACTIVE` by default |
| HTTP data queries | list, paged, count, version-range load | single, list, paged, count, and state-only |
| HTTP aggregation | `event/aggregation`, JSON or SSE | `snapshot/aggregation`, JSON or SSE |
| HTTP Schema | `event/schema` and refresh | `snapshot/schema` and refresh |
| API Client | None | Separate snapshot contracts exist |

## When to Use Event Stream Queries

Use event-stream queries for complete event history, the events produced by one command, version ranges, or event payloads. Use [Snapshot Queries](./snapshot-query.md) to read or filter current business state.
