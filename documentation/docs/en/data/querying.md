---
title: Query for Analysis
description: Answer analysts' questions with the Wow query language, counting, grouping by time and value, running totals and filtered groups, over snapshots, event streams and datasets, within the limits the service sets.
---

# Query for Analysis

An analysis is a query of kind `aggregate`: it filters the records, groups them, and computes metrics per group. The same body works on every model, snapshots, event streams and datasets, posted to the model's query route. The examples on this page run against the eShop catalog's products in its tests, as written here. The [Query Language](../reference/query-language) is the full grammar; this page is the analyst's path through it.

## Count by a value

Group with `TERMS` on a field, and compute metrics per group:

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/aggregate.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "NE", "field": "state.status", "value": "DRAFT" },
  "groupBy": [{ "type": "TERMS", "field": "state.status", "alias": "status" }],
  "metrics": [
    { "type": "COUNT", "alias": "count" },
    { "type": "NUMERIC", "function": "AVG", "expression": { "type": "FIELD", "field": "state.price" }, "alias": "averagePrice" }
  ],
  "sort": [{ "field": "count", "direction": "DESC" }]
}
```

The answer is one row per group, holding the group aliases and the metric aliases: `[{ "status": "PUBLISHED", "count": 3, "averagePrice": 7.5 }, …]`. `limit` caps the groups, 100 by default. A `count` query counts the records without grouping them:

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/count.json -->

```json
{
  "kind": "count",
  "filter": { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
  "limit": 1000
}
```

## Keep the groups that matter

`having` filters the groups by their metrics, after they are computed. Brands with at least two published products, the products of no brand grouped as `none`:

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/having.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
  "groupBy": [{ "type": "TERMS", "field": "state.brandId", "missingKey": "none", "alias": "brand" }],
  "metrics": [{ "type": "COUNT", "alias": "products" }],
  "having": { "type": "CONDITION", "metric": "products", "operator": "GTE", "value": 2 },
  "sort": [{ "field": "products", "direction": "DESC" }],
  "limit": 10
}
```

## Group by time and by range

`DATE_HISTOGRAM` groups a time field by a calendar unit, in a time zone; `HISTOGRAM` groups a number by intervals. Products listed per month over the last year, with a running total:

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/monthly.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "RECENT_DAYS", "field": "firstEventTime", "days": 365 },
  "groupBy": [{ "type": "DATE_HISTOGRAM", "field": "firstEventTime", "unit": "MONTH", "timeZone": "UTC", "alias": "month" }],
  "metrics": [
    { "type": "COUNT", "alias": "listed" },
    { "type": "DERIVED", "expression": { "type": "RUNNING", "metric": "listed", "order": { "field": "month", "direction": "ASC" } }, "alias": "listedSoFar" }
  ],
  "sort": [{ "field": "month", "direction": "ASC" }]
}
```

`RUNNING` accumulates a metric along an order of the groups. The other windows over groups are `TOTAL` (a share of the whole), `PREVIOUS` (the same metric one bucket or one period earlier, for growth against last month or last year) and `RANK`; a `DERIVED` metric can also combine metrics arithmetically, such as a conversion rate. Published products by price band of 10, with the cheapest in each:

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/price-bands.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
  "groupBy": [{ "type": "HISTOGRAM", "field": "state.price", "interval": 10, "alias": "band" }],
  "metrics": [
    { "type": "COUNT", "alias": "products" },
    { "type": "NUMERIC", "function": "MIN", "expression": { "type": "FIELD", "field": "state.price" }, "alias": "cheapest" }
  ],
  "sort": [{ "field": "band", "direction": "ASC" }]
}
```

`DATE_PART` groups by a part of the date instead, the day of the week or the hour of the day, to see when things happen. `"dense": true` fills the empty buckets of a date group that is the only group, so a chart has no gaps.

## Time relative to now

A filter on time is relative to the service's clock, read once per query, never the browser's, so a saved query means the same thing tomorrow:

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/relative-time.json -->

```json
{
  "kind": "list",
  "filter": { "op": "RECENT_DAYS", "field": "lastEventTime", "days": 7, "zoneId": "Asia/Shanghai" }
}
```

Calendar filters (`TODAY`, `THIS_WEEK`, `LAST_MONTH`, `RECENT_DAYS` and the others) read their days in the `zoneId` the filter names, and in UTC without one. `BEFORE_NOW` and `AFTER_NOW` compare with now plus an ISO-8601 offset. [Relative time](../reference/query-language#relative-time) lists them all.

## What happened, not what is

An analysis of snapshots counts what things are now. To count what happened, query the aggregate's event streams: one record per commit, its events in `events`. A condition on an event is an `ELEMENT_MATCH` on `events`, and an aggregation over events expands `events` with `elements`, so its groups and metrics run over each event ([Query Event Streams](../guide/event-queries)).

## Across aggregates and services

A snapshot or event query reads one aggregate type of one service. An analysis that joins orders with their customers, or sales across services, reads a dataset: a table defined over the warehouse, queried with the same body at `POST /wow/dataset/<context>/<name>/query` ([Datasets and the Analytics Catalog](./datasets)).

## Limits and cost

Every query runs under the budget of the service that serves it:

- A filter holds at most 128 nodes and 1,000 values over HTTP, and an aggregation answers 100 groups unless its `limit` says otherwise.
- Some operators cannot use an index, such as `NE`, `NOT_IN`, `CONTAINS` and `EXPRESSION`. A service can refuse them (`wow.query.budgets.http.allow-expensive-operators: false`).
- A query that runs longer than its budget is answered 504 `wow.query.timeout`, and one beyond the store's concurrent queries 429 `wow.query.busy`, with a `Retry-After`. Dataset queries have a bulkhead of their own (`wow.query.dataset.max-concurrent`, 8), so analyses never crowd out the service's own reads.

The capability descriptor, `GET` on the same route, says what each field admits and the limits that apply ([The capability descriptor](../reference/query-language#the-capability-descriptor)): read it before building a query a model may refuse.
