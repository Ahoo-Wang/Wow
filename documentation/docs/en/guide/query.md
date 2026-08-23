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

## Snapshot Elements Aggregation

Snapshot aggregation exposes only tabular semantics that MongoDB and Elasticsearch can execute exactly. The HTTP endpoint is
`POST {aggregate-path}/snapshot/aggregation` and supports both `application/json` and
`text/event-stream`. Every group key and metric uses an explicit alias.

### Kotlin DSL

```kotlin
aggregationQuery {
    filter { "state.status" eq "CREATED" }
    expand("state.items") {
        filter { "quantity" gt 0 }
        groupBy("productId", "productId")
        sum("totalPrice", "totalAmount")
        count("lineCount")
        sort { "totalAmount".desc() }
        limit(100)
    }
}
```

Fields inside an `expand` block are relative; the resulting `AggregationQuery` normalizes them to
absolute paths. `groupBy`, metrics, sort, and limit must be declared in the innermost scope, and
each scope has at most one child `expand`.

### HTTP JSON

When constructing the Kotlin model directly or sending JSON, all fields in Elements, filters,
groups, and metrics must be absolute. `type` is the Jackson discriminator for Group, Metric, and
Expression.
This new aggregation endpoint accepts `filter` only; legacy `condition` payloads are rejected.
Generated OpenAPI publishes separate Elements, Terms, Numeric, and Temporal field enums so clients
cannot select a field type that runtime validation would always reject.
The following example contains Elements and metric ordering, so set
`wow.webflux.query.allow-expensive-operators=true` before calling it; the default configuration
rejects this request.

::: code-group

```shell [Request]
curl -X POST \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/aggregation' \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
    "filter": {
      "op": "EQ",
      "field": "state.status",
      "value": "CREATED"
    },
    "elements": [
      {
        "path": "state.items",
        "filter": {
          "op": "GT",
          "field": "state.items.quantity",
          "value": 0
        }
      }
    ],
    "groupBy": [
      {
        "type": "TERMS",
        "field": "state.items.productId",
        "alias": "productId"
      }
    ],
    "metrics": [
      {
        "type": "NUMERIC",
        "function": "SUM",
        "expression": {
          "type": "FIELD",
          "field": "state.items.totalPrice"
        },
        "alias": "totalAmount"
      },
      {
        "type": "COUNT",
        "alias": "lineCount"
      }
    ],
    "sort": [
      {
        "field": "totalAmount",
        "direction": "DESC"
      }
    ],
    "limit": 100
  }'
```

```json [Response]
[
  {
    "productId": "product-1001",
    "totalAmount": 128.0,
    "lineCount": 4
  },
  {
    "productId": "product-1002",
    "totalAmount": 96.5,
    "lineCount": 2
  }
]
```

:::

Set `Accept` to `text/event-stream` to stream one result row at a time. Query semantics are
identical to the JSON response.

### Sources and Field Scope

- `elements=[]` aggregates root snapshots. Otherwise, Elements declare one strict parent-child object-collection chain from outermost to innermost.
- Elements accept only object collections or object arrays. Maps, scalar collections, duplicate paths, skipped parent collections, and sibling Cartesian products are rejected.
- `groupBy`, metric, and expression fields must belong to the innermost source. They cannot implicitly access a parent, sibling, or unexpanded child collection.
- Each `AggregationElement.filter` may reference only scalar fields or non-collection object paths in that element and must not use `ELEMENT_MATCH`, `SEARCH`, or `DELETION`.
- String operators require textual fields; range operators require numeric, temporal, or textual fields; relative-time operators require temporal fields. Object paths support only null/presence filters.
- A root `ELEMENT_MATCH` filters snapshots containing a match; it does not filter rows produced by expansion. Put row filters in the corresponding Element filter.
- Missing, `null`, and empty collections, including `null` collection members, produce no expanded rows. A row with any missing or `null` group field produces no bucket.

### Groups and Metrics

| Type | Input constraint | Result |
|---|---|---|
| `TERMS` | String, enum, UUID, Boolean, or numeric scalar; temporal fields are rejected | Integral keys normalize to `Long`; floating-point/Decimal keys normalize to `Double` |
| `HISTOGRAM` | Numeric scalar; `interval` must be finite and greater than 0; no offset in the first version | `Double` bucket key |
| `DATE_HISTOGRAM` | Supported temporal/`Date` field; unit is `YEAR`, `QUARTER`, `MONTH`, `WEEK`, `DAY`, `HOUR`, `MINUTE`, or `SECOND` | Epoch-millisecond `Long`; `WEEK` starts on Monday |
| `COUNT` | No field | Counts snapshots at the root or innermost expanded rows under Elements; returns `Long` |
| `NUMERIC` | `SUM`, `AVG`, `MIN`, or `MAX` with a numeric `FIELD` expression | `Double?`; missing values are ignored; an empty set yields `SUM=0.0` and `null` for the others |

`DateHistogram.timeZone` defaults to `UTC` and accepts only an IANA ID such as
`Asia/Shanghai` or an `±HH:MM` offset. Non-contract forms such as `Z` and `UTC+08:00`
are rejected. Any non-finite Numeric metric result fails the whole query.

### Results, Ordering, and Empty Sets

- Without `groupBy`, the query always returns one row, rejects sort, and validates limit even though limit cannot change the single result.
- A grouped query with no bucket returns an empty stream.
- The default order is ascending by group declaration order. Explicit sort appends every omitted group alias ascending as a stable tie-breaker.
- Sort fields must be unique and reference output aliases only. `null` sorts first ascending and last descending.
- Group-alias-only ordering can stop at limit. Any metric-alias ordering traverses every bucket and computes exact Top-N.
- Aliases are globally unique across groups and metrics. They must not be blank, contain `.`/NUL, start with `$` or `__wow_`, or equal `_id`.

### Limits and HTTP Guards

| Item | Public model | HTTP default |
|---|---:|---:|
| Elements depth | 5 | 3 |
| Element/group/expression field-path segments | 10 | 10 |
| groupBy entries | 32 | 32 |
| metrics entries | 1..64 | 1..32 |
| limit | Default 100; maximum 10,000 | Grouped queries are also capped by `max-list-size=1000` |
| Root plus all Element filters | — | At most `max-condition-nodes=64` nodes in total |

Setting `max-aggregation-elements`, `max-aggregation-metrics`, or `max-list-size` to `0`
disables that HTTP cap only; public hard limits still apply. The following requests also require
`allow-expensive-operators=true`:

- any Elements expansion;
- a root filter that remains match-all after trusted tenant/owner/space route scoping;
- sort referencing any metric alias.

The HTTP guard counts only user-submitted root and Element filters. Trusted tenant/owner/space route
filters do not consume that budget and may scope a match-all root before cost classification; ABAC is
applied later and does not change the classification. `AbacQueryFilter.resolveAggregationFilter`
is the aggregation-specific authorization hook; its default delegates with data-returning
`DYNAMIC_LIST` semantics, never `COUNT`. A custom Snapshot `QueryFilter` must provide its equivalent
aggregation policy through `SnapshotAggregationQueryFilterProvider`; otherwise the aggregation endpoint
fails closed instead of bypassing existing authorization or rewrite rules. Aggregation fails closed before backend access when a Snapshot masker is configured.
The HTTP layer does not maintain a duplicate field allowlist; the aggregation metadata Validator is
the single authority for collection chains, field ownership, and portable types.
Groups and metrics have no script entry point.

### Backend Failures and Performance Boundary

- Elasticsearch requires every Elements path to be `nested` and every `DateHistogram` field to be `date`/`date_nanos`. Plain `object` mappings and epoch `long` fields are rejected.
- MongoDB uses successive `$unwind` stages and forces `simple` collation for string grouping and ordering.
- Timeout, shard failure, missing response structure, conversion failure, or a non-finite metric result fails the whole query; partial results are never returned.

The current single-thread engineering baseline uses 10,000 snapshots with 100 leaf elements per
snapshot. Elements group-key ordering is approximately `393–1,639 ms/op` on MongoDB and
`1–8 ms/op` on Elasticsearch. Exact metric Top-N is approximately `1.61–1.84 s/op` on MongoDB and
`1.79 s/op` on Elasticsearch. These are point-estimate ranges from one JMH run (1 fork and 3
measurements, with high variance in some scenarios). They identify expensive operators; they are
not a production SLA, regression threshold, or cross-backend ranking. See the
[full benchmark report](https://github.com/Ahoo-Wang/Wow/blob/main/wow-benchmarks/results/reports/snapshot-elements.md).

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
