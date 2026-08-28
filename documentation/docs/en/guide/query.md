---
title: Query Service
description: Query snapshots and event streams with logical fields, FilterExpression, runtime query schemas, and guarded WebFlux routes.
---

# Query Service

The Wow read path has four distinct responsibilities:

```text
event -> snapshot/projection -> logical query model -> query service -> guarded HTTP route/API client
```

`wow-query` defines the query model and filter chain. `wow-mongo` and `wow-elasticsearch` map logical fields to backend-native queries. `wow-webflux` adds request-scope rewriting and HTTP cost guards. OpenAPI describes the wire shapes; an API client only calls the published routes. None of these layers creates authentication or an application-specific projection.

## FilterExpression

`FilterExpression` is the current filter contract. JSON uses `op` as the only discriminator:

```json
{
  "op": "AND",
  "operands": [
    {"op": "EQ", "field": "state.status", "value": "CREATED"},
    {"op": "DELETION", "state": "ACTIVE"}
  ]
}
```

A `field` is a logical path, not a MongoDB or Elasticsearch field name. Named segments start with a letter or underscore and may contain letters, digits, underscores, and hyphens; numeric segments address array indexes. For example, `state.items.0.productId` is valid. Backend adapters own physical paths and capabilities.

Snapshot queries default to `DELETION = ACTIVE`. An explicit `DELETION` suppresses that default when it is the root expression or appears at any depth in the root's recursive `AND` conjunction tree. A deletion nested under `OR` or `NOR` is not an explicit top-level conjunction scope, so the active guard remains. Event-stream queries keep the complete history and do not add the snapshot deletion scope.

### Operators

| Category | `op` | Main fields | Contract |
|---|---|---|---|
| Constants | `MATCH_ALL`, `MATCH_NONE` | - | Match every record or none |
| Metadata | `ID`, `IDS`, `AGGREGATE_ID`, `AGGREGATE_IDS`, `TENANT_ID`, `OWNER_ID`, `SPACE_ID` | `value` / `values` | Root-only document and message metadata filters |
| Logical | `AND`, `OR`, `NOR` | `operands` | At least one operand |
| Comparison | `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE` | `field`, `value` | `EQ`/`NE` normalize `null` to null predicates |
| String | `CONTAINS`, `STARTS_WITH`, `ENDS_WITH` | `field`, `value`, `stringComparison` | Default comparison is `CASE_SENSITIVE` |
| Collection | `IN`, `NOT_IN`, `CONTAINS_ALL` | `field`, `values` | Non-empty values; no `null` elements |
| Range | `BETWEEN` | `field`, `lowerBound`, `upperBound` | Inclusive bounds |
| Shape | `IS_EMPTY`, `IS_NULL`, `IS_NOT_NULL`, `EXISTS`, `NOT_EXISTS` | `field` | Backend-native empty/null/existence behavior |
| Deletion | `DELETION` | `state` | `ACTIVE`, `DELETED`, or `ALL` |
| Array element | `ELEMENT_MATCH` | `field`, `predicate` | Child predicate is element-relative and excludes root-only filters |
| Full text | `SEARCH` | `query`, `fields`, `mode` | `TERMS` by default; optional `PHRASE`; backend support differs |
| Relative time | `TODAY`, `YESTERDAY`, `BEFORE_TODAY`, `TOMORROW`, `THIS_WEEK`, `NEXT_WEEK`, `LAST_WEEK`, `THIS_MONTH`, `NEXT_MONTH`, `LAST_MONTH`, `LAST_YEAR`, `THIS_YEAR`, `NEXT_YEAR`, `RECENT_DAYS`, `EARLIER_DAYS` | `field` plus time options | Resolved to absolute ranges before backend compilation |

Numeric temporal fields use `timeUnit` (`MILLISECONDS` by default). A configured `datePattern` emits formatted strings and ignores `timeUnit`. The runtime query-model schema may declare `Temporal.Date`, `Temporal.Epoch`, or `Temporal.Formatted`; capability publication still depends on the backend's proven physical mapping.

::: info Backend boundary
MongoDB full-text search uses the collection text index and cannot limit `$text` to the requested `fields`. Elasticsearch resolves supported search fields and multi-fields. Use the runtime query-model schema to discover available capabilities; do not infer parity from the common JSON type.
:::

## Kotlin DSL

Build a standalone expression with `filterExpression`:

```kotlin
val filter = filterExpression {
    aggregateId("order-1")
    pathState {
        "status" eq "CREATED"
        "totalAmount" gte 100
        "items".elementMatch {
            "productId" eq "product-1"
            "quantity" gt 0
        }
    }
}
```

Expressions in one block are combined with `AND`; use `or`, `nor`, or `and` for explicit grouping. `String.path` creates a lexical path scope and nested scopes append relative names. `pathState` is shorthand for `"state".path`.

`ELEMENT_MATCH` starts an independent element-relative scope. Metadata filters, `DELETION`, and `SEARCH` are root expressions and cannot be placed inside it. `expression(...)` inserts a prebuilt expression only at the current query root; a prebuilt `LogicalField` is not automatically rebased.

### Query DSL

`singleQuery`, `listQuery`, and `pagedQuery` share filter, projection, and sort contracts:

```kotlin
val query = pagedQuery {
    filter {
        pathState {
            "status" eq "CREATED"
            "createTime".recentDays(7, ZoneId.of("Asia/Shanghai"))
        }
    }
    projection {
        include("aggregateId")
        include("state.status")
    }
    sort { "state.createTime".desc() }
    pagination {
        index(1)
        size(20)
    }
}

query.query(snapshotQueryService)
```

Pagination is 1-based. At the JVM query-service boundary, `ListQuery.limit = 0` means unlimited. The WebFlux HTTP guard rejects or caps requests according to `wow.webflux.query.*`; its defaults do not change the in-process query model.

Before backend execution, a `QuerySchemaResolver` resolves logical fields and capabilities. `wow.query.schema.validation-mode=COMPATIBLE` accepts `EXACT` and `COMPATIBLE` resolutions; `STRICT` accepts only `EXACT`. A compatible fallback is not proof that a field has the same physical behavior on every backend.

### Snapshot aggregation

`AggregationQuery` returns dynamic tabular rows and always requires at least one metric:

```kotlin
val query = aggregation {
    filter { "state.status" eq "PAID" }
    expand("state.items") { "quantity" gt 0 }
    terms("productId", "product")
    sum(field("price") * field("quantity"), "revenue")
    count("lineCount")
    sort { "revenue".desc() }
    limit(20)
}

query.query(snapshotQueryService)
```

Path relativity is part of the public contract:

- the root `filter` uses absolute snapshot paths;
- the first Element path is absolute;
- each later Element path is relative to the current expanded element;
- each Element filter is relative to its own element;
- group and metric fields are relative to the innermost Element, or absolute when no Element exists;
- Elements form one ordered parent-child chain, not sibling expansions.

Groups are `TERMS`, `HISTOGRAM`, and `DATE_HISTOGRAM`. Metrics are `COUNT`, `ANY`, and numeric `SUM`, `AVG`, `MIN`, `MAX`. Numeric expressions support finite constants plus `ADD`, `SUBTRACT`, `MULTIPLY`, and `DIVIDE`. `COUNT` is a `Long`; numeric metrics return a finite `Double` or `null` when no value contributes.

`ANY` selects one non-null scalar in a group. The selected value is intentionally unstable across executions and backends; it is not a deterministic replacement for another group key. A query without groups returns one summary row, including for an empty input (`COUNT = 0`, numeric metrics `null`).

Aliases are unique single-segment logical fields and cannot use the `__wow` prefix. Sort fields reference aliases. Wow appends missing group aliases in declaration order for stable ordering. Limits are 5 Elements, 32 groups, 64 metrics, 32 effective sort fields, expression depth 8, 256 expression nodes, and 10,000 result rows; the default row limit is 100.

The base HTTP route is `POST /{aggregate}/snapshot/aggregation`. For dynamic-tenant or owned aggregates, the catalog also contributes tenant- or owner-scoped query variants. The route uses the ordinary snapshot query filter chain, so request-scope and configured ABAC filters can extend the root filter. Result masking intentionally skips aggregation. `allow-expensive-operators=false` rejects Elements, metric-alias sorting, non-Field numeric expressions, and expensive operators in HTTP aggregation filters.

The REST compatibility extractor treats aggregation independently from single/list/paged: `filter` and legacy `condition` may both be omitted (the model defaults to `MATCH_ALL`), or exactly one may be supplied; supplying both is rejected. Request-scope rewriting still appends tenant/owner/space filters after extraction.

A custom `SnapshotQueryService` may inherit the default unsupported `aggregate()` implementation. Successful single/list/paged/count calls and a published route do not prove that custom service supports aggregation. Test the selected backend.

#### Scenario examples

All request bodies below are sent to the aggregate's `snapshot/aggregation` route. Use the running service's OpenAPI document for the exact scoped path.

##### Count records by category

```json
{
  "groupBy": [
    {"type": "TERMS", "field": "state.status", "alias": "status"}
  ],
  "metrics": [
    {"type": "COUNT", "alias": "count"}
  ],
  "sort": [{"field": "status", "direction": "ASC"}],
  "limit": 10
}
```

##### Summarize filtered records

Without groups, one row summarizes the filtered input:

```json
{
  "filter": {"op": "EQ", "field": "state.status", "value": "FAILED"},
  "metrics": [
    {"type": "COUNT", "alias": "failedCount"},
    {
      "type": "NUMERIC",
      "function": "AVG",
      "expression": {"type": "FIELD", "field": "state.retryState.retries"},
      "alias": "averageRetries"
    }
  ]
}
```

##### Inspect a numeric distribution

`HISTOGRAM` returns each bucket's lower bound:

```json
{
  "groupBy": [
    {"type": "HISTOGRAM", "field": "state.totalAmount", "alias": "amountRange", "interval": 100}
  ],
  "metrics": [{"type": "COUNT", "alias": "orderCount"}],
  "sort": [{"field": "amountRange", "direction": "ASC"}]
}
```

##### Track a business-time trend

`DATE_HISTOGRAM` needs the runtime schema to publish temporal aggregation capability. Bucket keys are epoch milliseconds at the bucket start:

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
  "metrics": [{"type": "COUNT", "alias": "createdCount"}]
}
```

MongoDB must prove a native BSON Date through collection metadata or use a declared numeric epoch. Elasticsearch uses native date/date_nanos mappings or a request-scoped runtime date for declared epoch fields. A formatted temporal string does not gain date-histogram capability merely because it has a date pattern.

##### Expand a collection and select Top-N

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
      "expression": {"type": "FIELD", "field": "quantity"},
      "alias": "totalQuantity"
    }
  ],
  "sort": [{"field": "totalQuantity", "direction": "DESC"}],
  "limit": 10
}
```

### Rewriting queries

Filters are immutable. Policy filters append a new expression instead of changing field meaning:

```kotlin
val requiredScope = filterExpression {
    "state.warehouseId" eq warehouseId
}
context.appendFilter(requiredScope)
```

The WebFlux `RewriteRequestFilter` appends tenant, owner, and space metadata filters derived from the route and header. This scopes the backend query; it does not authenticate the caller or prove that the caller may select those scope values.

## REST API

Built-in WebFlux handlers place the `ServerRequest` in Reactor Context. Only then does `HttpQueryGuardFilter` enforce list/page windows, filter nodes and values, expensive-operator policy, and idle timeouts. Injected query services and other non-WebFlux contexts do not receive those HTTP-only limits.

The schema resolver validates logical fields and backend capabilities. The HTTP guard limits request cost. Application security filters authorize the principal. These are separate boundaries.

### Paged query

```http
POST /tenant/tenant-1/sales-order/snapshot/paged
Content-Type: application/json
Wow-Space-Id: space-1
```

```json
{
  "filter": {"op": "EQ", "field": "state.status", "value": "CREATED"},
  "projection": {"include": ["aggregateId", "state.status"]},
  "sort": [{"field": "state.createTime", "direction": "DESC"}],
  "pagination": {"index": 1, "size": 20}
}
```

The prefix above is only an example of a tenant-scoped aggregate. Default local routes do not prepend a bounded-context alias. Generated OpenAPI is the route source of truth.

### List and single queries

List and single bodies use the same `filter`, `projection`, and `sort`. A list adds `limit`; a single has no pagination:

```json
{
  "filter": {"op": "AGGREGATE_ID", "value": "order-1"},
  "limit": 1,
  "sort": []
}
```

Use `/snapshot/list/state`, `/snapshot/paged/state`, or `/snapshot/single/state` for state-only response shapes. Logical query fields still use the full snapshot model, such as `state.status`; response unwrapping does not rename request fields.

### Count

The canonical count body is a `FilterExpression` directly, without an outer `filter`:

```http
POST /sales-order/snapshot/count
Content-Type: application/json

{"op": "EQ", "field": "state.status", "value": "CREATED"}
```

The REST compatibility extractor also accepts `{}` as legacy `Condition.ALL`, then applies any request-scope filters. If a discriminator is present, use either new `op` or legacy `operator`; using both is rejected. OpenAPI publishes only the canonical `FilterExpression` body.

On the JVM, use `filter.count(queryService)`. Count remains exact according to the selected backend contract; HTTP cost policy may reject an unfiltered count when expensive operators are disabled.

## Compatibility and migration

`Condition`, `Operator`, and `ConditionDsl` are deprecated compatibility inputs. Legacy constructors and count extensions convert them once to `FilterExpression`; the execution pipeline retains `filter`. The REST extractor rules are endpoint-specific:

| Endpoint body | Neither representation | New representation | Legacy representation | Both |
|---|---|---|---|---|
| single/list/paged | rejected | `filter` accepted | `condition` accepted | rejected |
| aggregation | accepted; omitted `filter` defaults to `MATCH_ALL` | `filter` accepted | `condition` accepted | rejected |
| count | accepted as legacy `Condition.ALL` | top-level `op` accepted | top-level `operator` accepted | rejected |

- OpenAPI publishes only the new query shapes; runtime legacy acceptance is not added to the canonical schemas;
- legacy `MATCH` converts to `SEARCH`; it is not allowed inside element match;
- legacy `RAW` has no replacement. Backend-native queries belong in application-owned, explicitly secured endpoints.

Do not silently change an existing field's meaning during migration. Add a new logical field or an explicit schema override, then validate old and new requests against the intended compatibility mode.

## JSON Schema

The canonical wire schemas are versioned independently from the runtime field directory:

- [`filter-expression.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/filter-expression.schema.json)
- [`single-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/single-query.schema.json)
- [`list-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/list-query.schema.json)
- [`paged-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/paged-query.schema.json)
- [`count-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/count-query.schema.json)

OpenAPI publishes three separate layers. Generic component schemas define the JSON shapes of `FilterExpression`, single/list/paged queries, and aggregation. Each aggregate-specific request-body component references one of those generic schemas and adds static `x-wow-query-fields`: an enum built from system fields plus fields inferred by `JsonQuerySchemaSource`. The extension is a design-time field directory, not a list of backend-proven capabilities.

`GET /{aggregate}/snapshot/schema` returns the third layer: current `QueryModelSchemaMetadata` with merged logical metadata and backend-proven capabilities; `POST /{aggregate}/snapshot/schema/refresh` refreshes it. These runtime schema routes deliberately omit tenant, owner, and aggregate-ID path variants because they describe the model, not caller-specific data. The common aggregate contract may still declare `Wow-Space-Id` for a spaced aggregate.

The runtime schema merges system fields with JSON-Schema inference, classpath conventions, bean registrations, and working-directory conventions, then lets the backend adapter resolve physical bindings. KSP-generated `*Properties` constants are compile-time navigation helpers; they are not this runtime schema and do not publish an HTTP route.

## Query service registrar

`SnapshotQueryServiceRegistrar` and `EventStreamQueryServiceRegistrar` register local aggregate services such as `order.SnapshotQueryService`. These type-safe aggregate services delegate through `QueryServiceProxy` to `QueryGateway`, where query rewriting, configured ABAC filters, and masking run before the backend.

```kotlin
class OrderReader(
    private val queryService: SnapshotQueryService<OrderState>,
) {
    fun get(id: String): Mono<OrderState> = singleQuery {
        filter { aggregateId(id) }
    }.query(queryService).toState().throwNotFoundIfEmpty()
}
```

Factories are lower-level backend entry points. A service created directly from `SnapshotQueryServiceFactory` or `EventStreamQueryServiceFactory` bypasses the generated handler chain. Keep raw factories inside trusted infrastructure code; do not expose them as an ordinary request path.
