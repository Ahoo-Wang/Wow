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
| Metadata | `ID`, `IDS`, `AGGREGATE_ID`, `AGGREGATE_IDS`, `TENANT_ID`, `OWNER_ID`, `SPACE_ID` | `value` or `values` | Query document IDs, aggregate IDs, or message metadata; valid only as query-root expressions |
| Logical | `AND`, `OR`, `NOR` | `operands` | `operands` must contain at least one expression |
| Comparison | `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE` | `field`, `value` | `EQ` and `NE` accept `null` and normalize to null predicates |
| String | `CONTAINS`, `STARTS_WITH`, `ENDS_WITH` | `field`, `value`, `stringComparison` | `stringComparison` defaults to `CASE_SENSITIVE` |
| Collection | `IN`, `NOT_IN`, `CONTAINS_ALL` | `field`, `values` | `values` must be non-empty and cannot contain `null` |
| Range | `BETWEEN` | `field`, `lowerBound`, `upperBound` | Both bounds are inclusive |
| Empty, null, and existence | `IS_EMPTY`, `IS_NULL`, `IS_NOT_NULL`, `EXISTS`, `NOT_EXISTS` | `field` | Compiled to each backend's native existence and empty-value semantics |
| Deletion | `DELETION` | `state` | `ACTIVE`, `DELETED`, or `ALL`; deletion is part of the filter model |
| Array element | `ELEMENT_MATCH` | `field`, `predicate` | `predicate` cannot contain `DELETION`, `SEARCH`, or metadata Filters |
| Full-text search | `SEARCH` | `query`, `fields`, `mode` | `mode` defaults to `TERMS` and may be set to `PHRASE`; field support is backend-specific |
| Relative time | `TODAY`, `YESTERDAY`, `BEFORE_TODAY`, `TOMORROW`, `THIS_WEEK`, `NEXT_WEEK`, `LAST_WEEK`, `THIS_MONTH`, `NEXT_MONTH`, `LAST_MONTH`, `LAST_YEAR`, `THIS_YEAR`, `NEXT_YEAR`, `RECENT_DAYS`, `EARLIER_DAYS` | `field`; operation-specific `time` or `days`; optional `zoneId`, `datePattern`, and `timeUnit` | Normalized to absolute ranges before backend compilation |

For numeric time fields, set `timeUnit` to a `java.util.concurrent.TimeUnit` enum name; it defaults to `MILLISECONDS`. When `datePattern` is configured, the filter emits strings and ignores `timeUnit`.

`field` is a logical field path. Valid examples are:

```text
state.status
state.items.0.productId
```

A named segment starts with a letter or underscore and may contain letters, digits, underscores, and hyphens. Pure numeric segments are valid array indexes. MongoDB and Elasticsearch query implementations own physical field mapping.

Aggregate-specific OpenAPI request bodies publish valid filter, projection, and sort paths in `x-wow-query-fields`. Use those paths even when a `/state` response unwraps the `state` object; for example, query the response property `status` as `state.status`.

Snapshot queries default to `DELETION = ACTIVE`. A top-level `DELETION`, or one used directly inside the top-level `AND`, explicitly overrides that scope; nesting deletion inside `OR` or `NOR` does not disable the active guard. Event-stream queries do not add a deletion scope, preserving complete audit history.

:::info Backend differences
MongoDB `SEARCH` uses the collection text index and does not restrict the query to `fields`; Elasticsearch can resolve search fields and multi-fields. `PHRASE` compiles to a quoted `$text` phrase in MongoDB and to `multi_match(type = phrase)` in Elasticsearch. Use relative child fields inside `ELEMENT_MATCH` for portable MongoDB and Elasticsearch behavior.
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

Use `PHRASE` to match consecutive terms produced by the backend analyzer. Omitting `mode` preserves the existing `TERMS` behavior:

```kotlin
val phraseFilter = filterExpression {
    search("event sourcing", SearchMode.PHRASE, "state.title", "state.description")
}
```

Use the dedicated functions to query aggregate and message metadata:

```kotlin
val filter = filterExpression {
    aggregateId("order-1")
    tenantId("tenant-1")
}
```

Metadata Filters are query-root expressions and cannot be nested inside `elementMatch`.

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

Use `String.path` to create a lexical path scope for relative fields; `pathState` is shorthand for `"state".path`. Nested `path` blocks append relative paths. Only paths starting with the current scope plus `.` are already qualified, so a field whose name equals the scope remains relative. Leaving a block automatically restores its parent path. Multiple expressions in a `path` block form one implicit `AND` operand, even when the block appears inside `or` or `nor`:

```kotlin
val filter = filterExpression {
    pathState {
        "status" eq "CREATED"
        "customer".path {
            "id" eq customerId
        }
    }
    "tenantId" eq tenantId
}
```

`expression(...)` adds a prebuilt expression only at the current query-context root and cannot be called inside a `path` scope, including through deprecated `nested` blocks. `deletion(...)` is also query-root scoped and is rejected inside `path`. Prebuilt `LogicalField` values must already match the insertion context; for example, the independent element root inside `elementMatch` uses element-relative paths.

### Query DSL

`singleQuery`, `listQuery`, and `pagedQuery` all use `filter {}`:

```kotlin
val query = pagedQuery {
    filter {
        "state.status" eq "CREATED"
        "state.createTime".recentDays(7, ZoneId.of("Asia/Shanghai"))
        "state.createTime".yesterday(ZoneId.of("Asia/Shanghai"))
        "state.createTime".nextMonth(ZoneId.of("Asia/Shanghai"))
        "state.createTime".thisYear(ZoneId.of("Asia/Shanghai"))
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

### Snapshot aggregation

Use `aggregation {}` to expand an ordered chain of state collections and return tabular rows:

```kotlin
val query = aggregation {
    expand("state.orders") { "status" eq "PAID" }
    expand("lines") { "quantity" gt 0 }
    terms("productId", "product")
    sum("amount", "total")
    sort { "total".desc() }
    limit(20)
}

query.query(snapshotQueryService)
```

The equivalent JSON is:

```json
{
  "elements": [
    {
      "path": "state.orders",
      "filter": { "op": "EQ", "field": "status", "value": "PAID" }
    },
    {
      "path": "lines",
      "filter": { "op": "GT", "field": "quantity", "value": 0 }
    }
  ],
  "groupBy": [
    { "type": "TERMS", "field": "productId", "alias": "product" }
  ],
  "metrics": [
    {
      "type": "NUMERIC",
      "function": "SUM",
      "expression": { "field": "amount" },
      "alias": "total"
    }
  ],
  "sort": [{ "field": "total", "direction": "DESC" }],
  "limit": 20
}
```

The first Element path is an absolute snapshot path. Every later Element path and every Element filter is relative to its current expanded element. Group and metric fields are relative to the innermost Element; without Elements, they are absolute snapshot paths. Elements form one parent-child chain, not sibling expansions.

`TERMS`, `HISTOGRAM`, and `DATE_HISTOGRAM` groups are supported. Metrics are `COUNT`, `SUM`, `AVG`, `MIN`, and `MAX`; `COUNT` returns `Long`, while numeric metrics return a finite `Double` or `null` when no value contributes. A query without groups returns one summary row, including an empty dataset (`COUNT = 0`, numeric metrics `null`). Grouped results contain at most `limit` rows; the default is `100` and the maximum is `10,000`.

Sort fields reference group or metric aliases. Missing group-alias sorts are appended in declaration order for stable results. Sorting by a metric alias is expensive and is controlled by the WebFlux `query.allow-expensive-operators` guard. Fixed structural limits are 5 Elements, 32 groups, 64 metrics, and 32 effective sort fields.

Aggregation uses the existing snapshot filter chain: ABAC and route filters still extend the root filter. The masking filter ignores aggregation queries, so configured maskers do not reject or rewrite aggregation results.

Wow validates the request structure, not field existence, collection shape, or physical field type. It does not maintain an aggregation field catalog or use `TypeFieldPaths` for validation. Equivalent behavior is not guaranteed for custom Jackson serializers, backend filter converters, or custom Elasticsearch mappings. Batch aggregation and arithmetic expressions are not included.

The HTTP endpoint is `POST /{aggregate}/snapshot/aggregation`. Tenant-, owner-, or space-scoped aggregates prepend their applicable route prefix; use the running instance's OpenAPI paths as the source of truth. JSON responses are arrays of dynamic objects; SSE streams one object at a time. OpenAPI publishes an aggregate-specific `AggregationQuery` request body whose `x-wow-query-fields` references that aggregate's `*AggregatedFields` component, while the JSON schema remains the generic `AggregationQuery` contract.

#### Scenario examples

The following request bodies are sent to the applicable aggregate's `snapshot/aggregation` endpoint. Except for the first example, the repeated `curl` wrapper is omitted to keep the aggregation structure visible.

##### Count records by category

The compensation control plane can count records by execution status:

```bash
curl --request POST 'http://localhost:8080/execution_failed/snapshot/aggregation' \
  --header 'Content-Type: application/json' \
  --data '{
    "groupBy": [
      {"type": "TERMS", "field": "state.status", "alias": "status"}
    ],
    "metrics": [
      {"type": "COUNT", "alias": "count"}
    ],
    "sort": [
      {"field": "status", "direction": "ASC"}
    ],
    "limit": 10
  }'
```

The response has the following shape; counts depend on the current data:

```json
[
  {"status": "FAILED", "count": 12},
  {"status": "SUCCEEDED", "count": 3}
]
```

##### Summarize filtered records

Without `groupBy`, a query returns exactly one row. This query first selects failed records, then reports their count, average retries, and maximum retries:

```json
{
  "filter": {"op": "EQ", "field": "state.status", "value": "FAILED"},
  "metrics": [
    {"type": "COUNT", "alias": "failedCount"},
    {
      "type": "NUMERIC",
      "function": "AVG",
      "expression": {"field": "state.retryState.retries"},
      "alias": "averageRetries"
    },
    {
      "type": "NUMERIC",
      "function": "MAX",
      "expression": {"field": "state.retryState.retries"},
      "alias": "maxRetries"
    }
  ]
}
```

```json
[
  {"failedCount": 12, "averageRetries": 1.5, "maxRetries": 4.0}
]
```

If no records match, one row is still returned: `failedCount` is `0`, and both numeric metrics are `null`.

##### Inspect a numeric distribution

Orders can be bucketed by total amount. With `interval: 100`, the buckets are `[0, 100)`, `[100, 200)`, and so on; `amountRange` is the lower bound:

```json
{
  "groupBy": [
    {
      "type": "HISTOGRAM",
      "field": "state.totalAmount",
      "alias": "amountRange",
      "interval": 100
    }
  ],
  "metrics": [
    {"type": "COUNT", "alias": "orderCount"},
    {
      "type": "NUMERIC",
      "function": "SUM",
      "expression": {"field": "state.totalAmount"},
      "alias": "totalAmount"
    }
  ],
  "sort": [{"field": "amountRange", "direction": "ASC"}],
  "limit": 20
}
```

```json
[
  {"amountRange": 0.0, "orderCount": 8, "totalAmount": 356.0},
  {"amountRange": 100.0, "orderCount": 5, "totalAmount": 642.0}
]
```

##### Track a business-time trend

Assuming `state.createdAt` is an executable date field, records can be counted per day in the Shanghai time zone:

```json
{
  "groupBy": [
    {
      "type": "DATE_HISTOGRAM",
      "field": "state.createdAt",
      "alias": "day",
      "unit": "DAY",
      "timeZone": "Asia/Shanghai"
    }
  ],
  "metrics": [{"type": "COUNT", "alias": "createdCount"}],
  "sort": [{"field": "day", "direction": "ASC"}],
  "limit": 31
}
```

```json
[
  {"day": 1787500800000, "createdCount": 18},
  {"day": 1787587200000, "createdCount": 23}
]
```

Date bucket keys are epoch milliseconds at the start of each bucket. MongoDB fields must be convertible to dates; Elasticsearch fields must be mapped as `date` or `date_nanos`.

##### Expand a collection and select Top-N

Order items form a collection. Expand `state.items` with an absolute path, then use relative paths to filter, group, and sum the items:

```json
{
  "filter": {"op": "EQ", "field": "state.status", "value": "PAID"},
  "elements": [
    {
      "path": "state.items",
      "filter": {"op": "GT", "field": "quantity", "value": 0}
    }
  ],
  "groupBy": [
    {"type": "TERMS", "field": "productId", "alias": "productId"}
  ],
  "metrics": [
    {
      "type": "NUMERIC",
      "function": "SUM",
      "expression": {"field": "quantity"},
      "alias": "totalQuantity"
    }
  ],
  "sort": [{"field": "totalQuantity", "direction": "DESC"}],
  "limit": 10
}
```

```json
[
  {"productId": "product-1", "totalQuantity": 42.0},
  {"productId": "product-2", "totalQuantity": 31.0}
]
```

The root `filter` still uses absolute snapshot paths. The Element filter, group, and metric fields are relative to the expanded item. Sorting by a metric alias such as `totalQuantity` is an expensive operation. With `query.allow-expensive-operators=false`, the HTTP guard rejects this example for both its Elements expansion and metric-alias sort; enable the setting or remove both capabilities.

The same `AggregationQuery` contract can run through MongoDB and Elasticsearch snapshot query services and return the same row shape. Backend-specific field mappings, nested models, and custom serialization remain subject to the constraints documented by each extension.

### Rewriting queries

Query filters use `withFilter` or `appendFilter`; internal paths no longer rewrite `Condition`:

```kotlin
val warehouseFilter = filterExpression {
    "state.warehouseId" eq warehouseId
}
context.appendFilter(warehouseFilter)
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
  "filter": { "op": "AGGREGATE_ID", "value": "order-1" },
  "limit": 1,
  "sort": []
}
```

### Count

On the JVM, call the typed extension directly: `filter.count(queryService)`. The `Condition.count(...)` extension remains available but is deprecated.

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

The legacy `Condition` DTO, `Operator`, and `ConditionDsl` remain available but are deprecated. Legacy query constructors, `QueryService.count(Condition)`, and `Condition.count(...)` convert `Condition` to `FilterExpression` immediately; query objects and the execution pipeline retain only `filter`.

During REST migration:

- `single`, `list`, and `paged` requests must contain exactly one of `filter` or `condition`;
- `count` requests must contain exactly one of the new `op` or legacy `operator` discriminators;
- OpenAPI publishes only the new `FilterExpression` shape;
- legacy `condition` payloads are accepted only when they convert to a valid `FilterExpression`; `MATCH` now follows `SEARCH` semantics, cannot appear inside `ELEM_MATCH`, and no longer uses the former Elasticsearch exact-field mapping;
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
            aggregateId(id)
        }
    }.query(queryService).toState().throwNotFoundIfEmpty()
}
```
