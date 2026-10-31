---
title: Query for Analysis
description: Answer analysts' questions with the Wow query language, counting, grouping by time and value, running totals and filtered groups, over snapshots, event streams and datasets, within the limits the service sets.
---

# Query for Analysis

An analysis is a query of kind `aggregate`: it filters the records, groups them, and computes metrics per group. The same body works on every model, snapshots, event streams and datasets, posted to the model's query route. The examples on this page run against the eShop catalog's products in its tests, as written here. The [Query Language](../reference/query-language) is the full grammar; this page is the analyst's path through it.

## Count by a value

Group with `terms` on a field, and compute metrics per group:

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/aggregate.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "ne", "field": "state.status", "value": "DRAFT" },
  "groupBy": [{ "type": "terms", "field": "state.status", "alias": "status" }],
  "metrics": [
    { "type": "count", "alias": "count" },
    { "type": "numeric", "function": "avg", "expression": { "type": "field", "field": "state.price" }, "alias": "averagePrice" }
  ],
  "sort": [{ "field": "count", "direction": "desc" }]
}
```

The answer is one row per group, holding the group aliases and the metric aliases: `[{ "status": "PUBLISHED", "count": 3, "averagePrice": 7.5 }, …]`. `limit` caps the groups, 100 by default. A `count` query counts the records without grouping them:

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/count.json -->

```json
{
  "kind": "count",
  "filter": { "op": "eq", "field": "state.status", "value": "PUBLISHED" },
  "limit": 1000
}
```

## Keep the groups that matter

`having` filters the groups by their metrics, after they are computed. Brands with at least two published products, the products of no brand grouped as `none`:

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/having.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "eq", "field": "state.status", "value": "PUBLISHED" },
  "groupBy": [{ "type": "terms", "field": "state.brandId", "missingKey": "none", "alias": "brand" }],
  "metrics": [{ "type": "count", "alias": "products" }],
  "having": { "type": "condition", "metric": "products", "operator": "gte", "value": 2 },
  "sort": [{ "field": "products", "direction": "desc" }],
  "limit": 10
}
```

## Group by time and by range

`date-histogram` groups a time field by a calendar unit, in a time zone; `histogram` groups a number by intervals. Products listed per month over the last year, with a running total:

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/monthly.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "recent-days", "field": "firstEventTime", "days": 365 },
  "groupBy": [{ "type": "date-histogram", "field": "firstEventTime", "unit": "month", "timeZone": "UTC", "alias": "month" }],
  "metrics": [
    { "type": "count", "alias": "listed" },
    { "type": "derived", "expression": { "type": "running", "metric": "listed", "order": { "field": "month", "direction": "asc" } }, "alias": "listedSoFar" }
  ],
  "sort": [{ "field": "month", "direction": "asc" }]
}
```

`running` accumulates a metric along an order of the groups. The other windows over groups are `total` (a share of the whole), `previous` (the same metric one bucket or one period earlier, for growth against last month or last year) and `rank`; a `derived` metric can also combine metrics arithmetically, such as a conversion rate. Published products by price band of 10, with the cheapest in each:

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/price-bands.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "eq", "field": "state.status", "value": "PUBLISHED" },
  "groupBy": [{ "type": "histogram", "field": "state.price", "interval": 10, "alias": "band" }],
  "metrics": [
    { "type": "count", "alias": "products" },
    { "type": "numeric", "function": "min", "expression": { "type": "field", "field": "state.price" }, "alias": "cheapest" }
  ],
  "sort": [{ "field": "band", "direction": "asc" }]
}
```

`date-part` groups by a part of the date instead, the day of the week or the hour of the day, to see when things happen. `"dense": true` fills the empty buckets of a date group that is the only group, so a chart has no gaps.

## Time relative to now

A filter on time is relative to the service's clock, read once per query, never the browser's, so a saved query means the same thing tomorrow:

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/relative-time.json -->

```json
{
  "kind": "list",
  "filter": { "op": "recent-days", "field": "lastEventTime", "days": 7, "zoneId": "Asia/Shanghai" }
}
```

Calendar filters (`today`, `this-week`, `last-month`, `recent-days` and the others) read their days in the `zoneId` the filter names, and in UTC without one. `before-now` and `after-now` compare with now plus an ISO-8601 offset. [Relative time](../reference/query-language#relative-time) lists them all.

## What happened, not what is

An analysis of snapshots counts what things are now. To count what happened, query the aggregate's event streams: one record per commit, its events in `events`. A condition on an event is an `element-match` on `events`, and an aggregation over events expands `events` with `elements`, so its groups and metrics run over each event ([Query Event Streams](../guide/event-queries)).

## Across aggregates and services

A snapshot or event query reads one aggregate type of one service. An analysis that joins orders with their customers, or sales across services, reads a dataset: a table defined over the warehouse, queried with the same body at `POST /wow/dataset/<context>/<name>/query` ([Datasets and the Analytics Catalog](./datasets)).

## Limits and cost

Every query runs under the budget of the service that serves it:

- A filter holds at most 128 nodes and 1,000 values over HTTP, and an aggregation answers 100 groups unless its `limit` says otherwise.
- Some operators cannot use an index, such as `ne`, `not-in`, `contains` and `expression`. A service can refuse them (`wow.query.budgets.http.allow-expensive-operators: false`).
- A query that runs longer than its budget is answered 504 `wow.query.timeout`, and one beyond the store's concurrent queries 429 `wow.query.busy`, with a `Retry-After`. Dataset queries have a bulkhead of their own (`wow.query.dataset.max-concurrent`, 8), so analyses never crowd out the service's own reads.

The capability descriptor, `GET` on the same route, says what each field admits and the limits that apply ([The capability descriptor](../reference/query-language#the-capability-descriptor)): read it before building a query a model may refuse.
