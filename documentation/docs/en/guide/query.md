---
title: Query Service
description: Query snapshots and event streams with FilterExpression, the query DSL, and REST APIs.
---

# Query Service

`wow-mongo` and `wow-elasticsearch` provide query service implementations. The query API uses the single-layer `FilterExpression` model; storage modules compile it into backend queries.

## FilterExpression

`FilterExpression` is a sealed interface. Every JSON expression uses only `op` as its type discriminator; there is no duplicate `type` or `operator` field.

```json
{
  "op": "AND",
  "operands": [
    { "op": "EQ", "field": "state.status", "value": "CREATED" },
    { "op": "DELETION", "state": "ACTIVE" }
  ]
}
```

### Operators

| Category | `op` | Main fields | Semantics |
|---|---|---|---|
| Constants | `MATCH_ALL`, `MATCH_NONE` | - | Match every record or no records |
| Logical | `AND`, `OR`, `NOR` | `operands` | `operands` must contain at least one expression |
| Comparison | `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE` | `field`, `value` | `EQ` and `NE` accept `null` and normalize to null predicates |
| String | `CONTAINS`, `STARTS_WITH`, `ENDS_WITH` | `field`, `value`, `stringComparison` | `stringComparison` defaults to `CASE_SENSITIVE` |
| Collection | `IN`, `NOT_IN`, `CONTAINS_ALL` | `field`, `values` | `values` must be non-empty and cannot contain `null` |
| Range | `BETWEEN` | `field`, `lowerBound`, `upperBound` | Both bounds are inclusive |
| Empty, null, and existence | `IS_EMPTY`, `IS_NULL`, `IS_NOT_NULL`, `EXISTS`, `NOT_EXISTS` | `field` | Compiled to each backend's native existence and empty-value semantics |
| Deletion | `DELETION` | `state` | `ACTIVE`, `DELETED`, or `ALL`; deletion is part of the filter model |
| Array element | `ELEMENT_MATCH` | `field`, `predicate` | `predicate` cannot contain `DELETION` or `SEARCH` |
| Full-text search | `SEARCH` | `query`, `fields` | `query` cannot be blank; field support is backend-specific |
| Relative time | `TODAY`, `BEFORE_TODAY`, `TOMORROW`, `THIS_WEEK`, `NEXT_WEEK`, `LAST_WEEK`, `THIS_MONTH`, `LAST_MONTH`, `RECENT_DAYS`, `EARLIER_DAYS` | `field`; operation-specific `time` or `days`; optional `zoneId` | Normalized to absolute ranges before backend compilation |

`field` is a logical field path. Valid examples are:

```text
state.status
state.items.0.productId
```

A named segment starts with a letter or underscore and may contain letters, digits, underscores, and hyphens. Pure numeric segments are valid array indexes. MongoDB and Elasticsearch query implementations own physical field mapping.

Snapshot queries default to `DELETION = ACTIVE`. A top-level `DELETION`, or one used directly inside the top-level `AND`, explicitly overrides that scope; nesting deletion inside `OR` or `NOR` does not disable the active guard. Event-stream queries do not add a deletion scope, preserving complete audit history.

:::info Backend differences
MongoDB `SEARCH` uses the collection text index and does not restrict the query to `fields`; Elasticsearch can resolve search fields and multi-fields. Use relative child fields inside `ELEMENT_MATCH` for portable MongoDB and Elasticsearch behavior.
:::

## Kotlin DSL

Use `filterExpression` to build a standalone filter:

```kotlin
val orderFilter = filterExpression {
    deletion(DeletionState.ACTIVE)
    "state.status" eq "CREATED"
    "state.totalAmount" gte 100
    "state.customerName".contains(
        "wang",
        StringComparison.CASE_INSENSITIVE,
    )
    "state.tags" containsAll listOf("priority", "online")
    "state.items".elementMatch {
        "productId" eq "product-1"
        "quantity" gt 0
    }
}
```

Multiple expressions in the same DSL block are combined with `AND`. Use `and`, `or`, or `nor` for explicit grouping:

```kotlin
val filter = filterExpression {
    or {
        "state.status" eq "CREATED"
        "state.status" eq "PAID"
    }
    nor {
        "state.channel" eq "TEST"
    }
}
```

### Query DSL

`singleQuery`, `listQuery`, and `pagedQuery` all use `filter {}`:

```kotlin
val query = pagedQuery {
    filter {
        "state.status" eq "CREATED"
        "state.createTime".recentDays(7, ZoneId.of("Asia/Shanghai"))
    }
    projection {
        include("aggregateId")
        include("state.status")
    }
    sort {
        "state.createTime".desc()
    }
    pagination {
        index(1)
        size(20)
    }
}

query.query(queryService)
```

`ListQuery.limit = 0` means unlimited results. HTTP queries remain subject to the WebFlux query-cost guard configuration.

### Rewriting queries

Query filters use `withFilter` or `appendFilter`; internal paths no longer rewrite `Condition`:

```kotlin
context.asRewritableQuery().rewriteQuery { query ->
    val warehouseFilter = filterExpression {
        "state.warehouseId" eq warehouseId
    }
    query.appendFilter(warehouseFilter)
}
```

## REST API

### Paged query

```http
POST /tenant/tenant-1/sales-order/snapshot/paged
Content-Type: application/json
Wow-Space-Id: space-1
```

```json
{
  "filter": {
    "op": "AND",
    "operands": [
      { "op": "EQ", "field": "state.status", "value": "CREATED" },
      { "op": "DELETION", "state": "ACTIVE" }
    ]
  },
  "projection": {
    "include": ["aggregateId", "state.status"]
  },
  "sort": [
    { "field": "state.createTime", "direction": "DESC" }
  ],
  "pagination": {
    "index": 1,
    "size": 20
  }
}
```

### List and single queries

List and single requests also use `filter`. A list request adds `limit`; a single request has no pagination field:

```json
{
  "filter": { "op": "EQ", "field": "aggregateId", "value": "order-1" },
  "limit": 1,
  "sort": []
}
```

### Count

The count request body is a `FilterExpression` directly, without an outer `filter` property:

```http
POST /tenant/tenant-1/sales-order/snapshot/count
Content-Type: application/json
```

```json
{
  "op": "EQ",
  "field": "state.status",
  "value": "CREATED"
}
```

New payloads use strict deserialization. Unknown properties, missing required fields, empty logical operands, invalid logical fields, and values that violate the declared type constraints are rejected as request errors.

## Compatibility and migration

The legacy `Condition`, `Operator`, `ConditionDsl`, `ConditionCapable`, and `RewritableCondition` APIs remain available but are deprecated. Legacy query constructors and `QueryService.count(Condition)` still adapt `Condition` to `FilterExpression`.

During REST migration:

- `single`, `list`, and `paged` requests must contain exactly one of `filter` or `condition`;
- `count` requests must contain exactly one of the new `op` or legacy `operator` discriminators;
- OpenAPI publishes only the new `FilterExpression` shape;
- legacy `condition` payloads remain readable, but new clients should use `filter` immediately;
- `RAW` is removed without a replacement operator. Backend-native queries belong in application-owned endpoints with application-owned security policy.

Legacy payload example:

```json
{
  "condition": {
    "field": "state.status",
    "operator": "EQ",
    "value": "CREATED"
  },
  "limit": 20
}
```

## JSON Schema

Canonical schemas:

- [`filter-expression.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/filter-expression.schema.json)
- [`single-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/single-query.schema.json)
- [`list-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/list-query.schema.json)
- [`paged-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/paged-query.schema.json)
- [`count-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/count-query.schema.json)

## Query service registrar

`SnapshotQueryServiceRegistrar` registers local aggregate query services in the Spring container. The bean name is `aggregate name + ".SnapshotQueryService"`.

```kotlin
class OrderService(
    private val queryService: SnapshotQueryService<OrderState>,
) {
    fun getById(id: String): Mono<OrderState> = singleQuery {
        filter {
            "aggregateId" eq id
        }
    }.query(queryService).toState().throwNotFoundIfEmpty()
}
```
