---
title: Snapshot Aggregation
description: Apply snapshot aggregation to root documents and collection elements through eight business scenarios.
---

# Snapshot Aggregation

Snapshot aggregation treats each aggregate's current materialized state as the source of truth and returns dynamic table rows whose columns are group and metric aliases. See [Aggregation Queries](./aggregation-query.md) for the shared AST, aliases, sorting, and structural limits. This page applies that contract to snapshots.

## Capabilities and Entry Points

- **JVM Gateway**: inject the aggregate-scoped `SnapshotQueryService<OrderState>` through Spring, build an `AggregationQuery`, and call `query.query(snapshotQueryService)`. This bean normally executes policies through [QueryGateway](./query-gateway.md); see [Query Backends](./query-backend.md) for direct-Factory and custom-bean bypass conditions.
- **HTTP / OpenAPI**: the example domain publishes `POST /sales-order/snapshot/aggregation`, `POST /tenant/{tenantId}/sales-order/snapshot/aggregation`, and `POST /owner/{ownerId}/sales-order/snapshot/aggregation`. The request body is `AggregationQuery` JSON, and the response can negotiate `application/json` or `text/event-stream`. Use the running service's generated [OpenAPI](../open-api.md) for exact paths and scope parameters.
- **Snapshot API Client**: reactive and synchronous clients use the separate `ReactiveSnapshotAggregationQueryApi` and `SynchronousSnapshotAggregationQueryApi`; they are not folded into the regular snapshot-query interfaces. See the [general API Client guide](../extensions/apiclient.md) for dependencies and invocation.

Every HTTP JSON block below is a request body for one of these `snapshot/aggregation` routes. Results are representative dynamic rows, not fixed business data.

## Field Paths and Counting Units

Without `elements`, the root filter, groups, and metrics use absolute snapshot logical paths such as `state.status`; one record is one current root snapshot document. Business fields under `state` need the corresponding filter, group, or numeric capabilities from the [Query Model Schema (current guidance)](./query-model-schema.md).

After `expand("state.items")`, the counting unit becomes one expanded order item. The first Element path remains absolute, while its filter and all following group, metric, and expression fields are relative to that element. Use `quantity`, `productId`, and `price`, not `state.items.quantity`. Groups only bucket records and do not change the counting unit; `COUNT` always counts the current innermost scope.

## Scenario 1: Status Breakdown

**Business question**

How many current order snapshots are in each status?

**Counting unit**

Root snapshot documents; each current order snapshot is counted once.

**Kotlin DSL**

```kotlin
val query = aggregation {
    terms("state.status", "status")
    count("count")
}
```

**HTTP JSON and result interpretation**

```json
{
  "groupBy": [
    {"type": "TERMS", "field": "state.status", "alias": "status"}
  ],
  "metrics": [
    {"type": "COUNT", "alias": "count"}
  ]
}
```

```json
[
  {"status": "PAID", "count": 42},
  {"status": "FAILED", "count": 8}
]
```

`status` is the group column, and `count` is the number of snapshots in each group. `state.status` must have TERMS aggregation capability. For example, Elasticsearch normally needs an aggregatable keyword field; an arbitrary text mapping is not equivalent.

## Scenario 2: Filtered KPIs

**Business question**

How many orders have failed, and how many retries did they average?

**Counting unit**

Failed snapshots matching `state.status = FAILED`; with no group, all failed snapshots are summarized into one row.

**Kotlin DSL**

```kotlin
val query = aggregation {
    filter { "state.status" eq "FAILED" }
    count("failedCount")
    avg("state.retryState.retries", "averageRetries")
}
```

**HTTP JSON and result interpretation**

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

```json
[
  {"failedCount": 8, "averageRetries": 2.5}
]
```

`failedCount` counts failed snapshots. `averageRetries` averages only contributing numeric retry counts and is `null` when no numeric value contributes. The fields need exact-match and numeric-aggregation capability, respectively.

## Scenario 3: Numeric Range Distribution

**Business question**

Which 100-unit ranges contain the current order amounts?

**Counting unit**

Root snapshot documents; each current order snapshot enters one amount bucket.

**Kotlin DSL**

```kotlin
val query = aggregation {
    histogram("state.totalAmount", 100.0, "amountRange")
    count("orderCount")
}
```

**HTTP JSON and result interpretation**

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
    {"type": "COUNT", "alias": "orderCount"}
  ]
}
```

```json
[
  {"amountRange": 0.0, "orderCount": 12},
  {"amountRange": 100.0, "orderCount": 27}
]
```

`amountRange` is the lower bound of each `100`-wide bucket, and `orderCount` is the number of snapshots in that bucket. `state.totalAmount` must have numeric-histogram capability; a valid JSON shape cannot make an invalid or nonnumeric field aggregatable.

## Scenario 4: Business-Time Trend

**Business question**

How are current order creations distributed across Shanghai business days?

**Counting unit**

Root snapshot documents; each current order snapshot enters one day according to the business field `state.createdAt`.

**Kotlin DSL**

```kotlin
val query = aggregation {
    dateHistogram(
        "state.createdAt",
        AggregationDateUnit.DAY,
        "day",
        ZoneId.of("Asia/Shanghai"),
    )
    count("createdCount")
}
```

**HTTP JSON and result interpretation**

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
  "metrics": [
    {"type": "COUNT", "alias": "createdCount"}
  ]
}
```

```json
[
  {"day": 1787846400000, "createdCount": 31},
  {"day": 1787932800000, "createdCount": 24}
]
```

`day` is the bucket-start epoch milliseconds aligned to `Asia/Shanghai`, and `createdCount` is the number of snapshots in the bucket. `state.createdAt` is a business time defined by order state. Root `createTime` is an event-stream record field and is not part of the `MaterializedSnapshot` root model, so it cannot replace this field. MongoDB must prove a BSON Date or a declared numeric epoch, while Elasticsearch must prove date/date_nanos or runtime-date capability for a declared epoch. A formatted string does not gain date-histogram capability merely from a date pattern.

## Scenario 5: Line-Item Top-N

**Business question**

Which products have the highest valid purchased quantity in paid orders?

**Counting unit**

Expanded order items; only items with `quantity > 0` from root snapshots whose `state.status` is `PAID` contribute. Multiple items in one order are counted and summed separately.

**Kotlin DSL**

```kotlin
val query = aggregation {
    filter { "state.status" eq "PAID" }
    expand("state.items") { "quantity" gt 0 }
    terms("productId", "productId")
    sum("quantity", "totalQuantity")
    sort { "totalQuantity".desc() }
    limit(10)
}
```

**HTTP JSON and result interpretation**

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
  "sort": [
    {"field": "totalQuantity", "direction": "DESC"}
  ],
  "limit": 10
}
```

```json
[
  {"productId": "product-1", "totalQuantity": 96.0},
  {"productId": "product-2", "totalQuantity": 71.0}
]
```

Sorting references the metric alias `totalQuantity`, and `limit: 10` makes this a Top-N query. `state.items` must have Element-scope capability; after expansion, `productId` and `quantity` are element-relative paths. Elasticsearch needs the corresponding nested mapping to preserve same-item field correlation. When expensive operators are disabled, HTTP rejects both Elements and metric-alias sorting.

## Scenario 6: Derived Amount Metric

**Business question**

What is each product's net amount, `price × quantity - discount`, across all order items?

**Counting unit**

Expanded order items; derive an amount for each item before summing by product.

**Kotlin DSL**

```kotlin
val query = aggregation {
    expand("state.items")
    terms("productId", "productId")
    sum(
        field("price") * field("quantity") - field("discount"),
        "netAmount",
    )
}
```

**HTTP JSON and result interpretation**

```json
{
  "elements": [
    {"path": "state.items"}
  ],
  "groupBy": [
    {"type": "TERMS", "field": "productId", "alias": "productId"}
  ],
  "metrics": [
    {
      "type": "NUMERIC",
      "function": "SUM",
      "expression": {
        "type": "BINARY",
        "operator": "SUBTRACT",
        "left": {
          "type": "BINARY",
          "operator": "MULTIPLY",
          "left": {"type": "FIELD", "field": "price"},
          "right": {"type": "FIELD", "field": "quantity"}
        },
        "right": {"type": "FIELD", "field": "discount"}
      },
      "alias": "netAmount"
    }
  ]
}
```

```json
[
  {"productId": "product-1", "netAmount": 1280.0},
  {"productId": "product-2", "netAmount": 930.0}
]
```

`netAmount` is the sum of valid numeric-expression contributions in each group. All three operands are relative to one order item and need numeric-aggregation capability. HTTP rejects this non-Field expression when `allow-expensive-operators=false`.

## Scenario 7: Multidimensional Cross-Analysis

**Business question**

How are current orders distributed across the status and channel dimensions?

**Counting unit**

Root snapshot documents; each current order snapshot enters one status-and-channel combination.

**Kotlin DSL**

```kotlin
val query = aggregation {
    terms("state.status", "status")
    terms("state.channel", "channel")
    count("count")
}
```

**HTTP JSON and result interpretation**

```json
{
  "groupBy": [
    {"type": "TERMS", "field": "state.status", "alias": "status"},
    {"type": "TERMS", "field": "state.channel", "alias": "channel"}
  ],
  "metrics": [
    {"type": "COUNT", "alias": "count"}
  ]
}
```

```json
[
  {"status": "PAID", "channel": "APP", "count": 28},
  {"status": "PAID", "channel": "WEB", "count": 14}
]
```

Groups are declared in `status`, then `channel` order, and the two aliases identify each cross bucket. Both fields need TERMS capability; the number of returned combinations remains bounded by query `limit` and the HTTP result limit.

## Scenario 8: Display Fields for Groups

**Business question**

How many order items belong to each product ID, with one product name added for display?

**Counting unit**

Expanded order items; `lineCount` counts order items in each product group, not order snapshots.

**Kotlin DSL**

```kotlin
val query = aggregation {
    expand("state.items")
    terms("productId", "productId")
    any("name", "name")
    count("lineCount")
}
```

**HTTP JSON and result interpretation**

```json
{
  "elements": [
    {"path": "state.items"}
  ],
  "groupBy": [
    {"type": "TERMS", "field": "productId", "alias": "productId"}
  ],
  "metrics": [
    {"type": "ANY", "field": "name", "alias": "name"},
    {"type": "COUNT", "alias": "lineCount"}
  ]
}
```

```json
[
  {"productId": "product-1", "name": "Keyboard", "lineCount": 19},
  {"productId": "product-2", "name": "Mouse", "lineCount": 15}
]
```

`ANY` is suitable only for a display field whose value is stable within each `productId` group. If one product ID has multiple `name` values, the selected non-null value is unstable across executions or backends. For deterministic results, repair the business data or model the name as a deterministic group key instead of relying on `ANY`.

## Backend Capabilities and Stability Boundaries

- Snapshot queries append `DELETION = ACTIVE` by default. The root filter first selects snapshots, and each Element filter then selects individual expanded elements.
- The runtime Query Model Schema and selected MongoDB or Elasticsearch mapping jointly prove whether logical fields support exact match, range, Element scope, TERMS, numeric, or temporal aggregation. A valid request DTO does not establish backend support.
- The HTTP route executes through `SnapshotQueryGateway`, request-scope rewriting, and `HttpQueryGuardFilter`. When expensive operators are disabled, HTTP rejects Elements, metric-alias sorting, and arithmetic expressions. In-process JVM calls do not automatically receive these HTTP-only limits.
- Aggregation result masking is intentionally skipped. Authorization, tenant/owner/space scope, and sensitive-field modeling must be enforced before aggregation; result masking cannot repair a leak afterward.
- MongoDB and Elasticsearch share the public AST but do not promise identical physical pipelines, mappings, null handling, or bucket details. `ANY` in particular provides no stable value across executions or backends.
- A custom `SnapshotQueryService` may retain the default unsupported aggregation implementation. Working data-query routes or published OpenAPI alone do not prove that the custom backend executes aggregation.
