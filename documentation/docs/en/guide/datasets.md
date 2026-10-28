---
title: Publish Datasets from a Service
description: Declare a dataset over a service's warehouse tables in a file, scope its rows to their callers, mask its fields, and query it like a snapshot model.
outline: deep
---

# Publish Datasets from a Service

A dataset is a read-only table for analysis, declared over the service's warehouse tables and queried like a snapshot model: filters, groups and metrics, in the caller's scope. A service declares its own datasets in files; the analytics catalog defines datasets across services. This page declares one. Dataset queries are experimental in 10.0: their API may still change.

## Declare a dataset

A dataset is a JSON file named `<context>.<dataset>.json`, on the class path under `META-INF/wow/dataset/`. The eShop's ordering service declares its sales over the ClickHouse view of its orders:

<!-- sample: examples/eshop/ordering/ordering-server/src/main/resources/META-INF/wow/dataset/eshop-ordering.sales.json -->

```json
{
  "title": "Sales",
  "identity": "id",
  "eventTime": "commit_time",
  "scope": { "tenant": "tenant_id", "owner": "owner_id", "space": "space_id" },
  "storage": { "table": "eshop_ordering_order_state_flat" },
  "fields": {
    "id": { "types": ["string"], "nullable": false, "description": "The commit of the order's current state: ref@version." },
    "tenant_id": { "types": ["string"], "nullable": false },
    "aggregate_id": { "types": ["string"], "nullable": false, "description": "The order's id." },
    "owner_id": { "types": ["string"], "nullable": false, "description": "The buyer whose checkout the order is: its owner." },
    "space_id": { "types": ["string"], "nullable": false },
    "version": { "types": ["integer"], "nullable": false },
    "commit_time": { "types": ["string"], "semantic": { "type": "temporal-date" }, "nullable": false },
    "deleted": { "types": ["boolean"], "nullable": false },
    "status": { "types": ["string"], "nullable": false, "description": "SUBMITTED, AWAITING_VALIDATION, STOCK_CONFIRMED, PAID, SHIPPED or CANCELLED." },
    "totalAmount": { "types": ["decimal"], "nullable": false, "description": "The order's total: each line's unit price times its quantity." }
  }
}
```

| Property | Declares |
| --- | --- |
| `title` | What the dataset is called |
| `identity` | The column that identifies a row |
| `eventTime` | The column a relative-time filter reads by default |
| `scope` | The columns of each row's tenant, owner and space, and optionally its tags: a row is shown only to callers whose scope it is in. `"platform": true` instead marks a dataset every tenant reads |
| `storage` | The backend's binding: on ClickHouse, the `table` it reads |
| `fields` | Each column: its `types`, `nullable`, an optional `semantic` type, `enum` values and a `description` |

A field may also be masked, with `"sensitive": {"level": "confidential", "keepSuffix": 4}`, as a `@Sensitive` state field is.

A file in the working directory, `<wow.query.dataset.config-directory>/wow/dataset/` (`config/wow/dataset/` by default), takes precedence over the class path's file of the same name, so an operator can adjust a dataset without a new build.

## Where the rows come from

Datasets are served when a dataset backend is wired; in 10.0, ClickHouse's: `wow-clickhouse` on the class path, with `wow.clickhouse.endpoint` set or a ClickHouse `Client` bean. The ClickHouse sink, switched on by `wow.clickhouse.sink.enabled: true`, writes each published aggregate's commits to tables a dataset reads, such as `<context>_<aggregate>_state_flat`, the current states with the state's scalar fields as columns ([ClickHouse](../operate/clickhouse)).

## Query it

| Request | Answers |
| --- | --- |
| `POST /wow/dataset/<context>/<dataset>/query` | A query of the dataset |
| `GET /wow/dataset/<context>/<dataset>/query` | Its capability descriptor |
| `GET /wow/dataset` | The datasets the caller sees |

The body is a query of the [Query Language](../reference/query-language): a buyer's sales by status, say, is an `aggregate` grouped by `status` with a sum of `totalAmount`. Each caller reads only the rows of its own tenant, owner and space. In Kotlin, `QueryApi`'s `datasets(DatasetName.of(context, dataset), rowType)` runs the same queries; like the aggregation it serves, it is experimental (`@OptIn(ExperimentalWowApi::class)`).

`wow.query.dataset.max-concurrent` (8) bounds how many dataset queries a node runs at once, under a bulkhead of their own, so analytics never take the warehouse the sink is writing to; a query beyond it waits briefly, then answers `429 wow.query.busy`. A dataset that is served but cannot be queried now answers `409 wow.query.dataset-unavailable`, and an unknown one `404 wow.query.dataset-not-found`.

## Datasets across services

A dataset that joins the tables of several services, or that analysts define and review without a release, belongs to the analytics catalog, which publishes each version as a ClickHouse view that every node serves the same way ([Platform Services](../migrate/platform)).
