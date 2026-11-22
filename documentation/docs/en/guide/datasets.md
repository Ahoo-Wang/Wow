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
| `scope` | The columns of each row's tenant, owner and space: a row is shown only to callers whose scope it is in. `"platform": true` instead marks a dataset every tenant reads. `access` names the aggregate each row is of, whose access policy then decides the rows ([below](#rows-of-an-aggregate-with-an-access-policy)) |
| `storage` | The backend's binding: on ClickHouse, the `table` it reads |
| `lineage` | Where its protection comes from: `source`, the default, or `none` ([below](#protection)) |
| `fields` | Each column: its `types`, `nullable`, an optional `semantic` type, `enum` values and a `description` |

A field may also declare its own mask, with `"sensitive": {"level": "confidential", "keepSuffix": 4}`, stricter than its source's ([below](#protection)).

A file in the working directory, `<wow.query.dataset.config-directory>/wow/dataset/` (`config/wow/dataset/` by default), takes precedence over the class path's file of the same name, so an operator can adjust a dataset without a new build.

## Protection follows the source {#protection}

A dataset's columns are protected as their source is, whether the dataset declares them `sensitive` or not. When the table is one the ClickHouse sink writes for an aggregate of the service's own Manifest, a column that holds a value the state marks [`@Sensitive`](./queries#describe-what-types-cannot) is masked in every result, and compared only as a state read compares it: a `CONFIDENTIAL` column cannot be filtered on or sorted by its value. Wow reads the protection with the function the sink uses for its `wow_catalog` rows, from the same Manifest, so the dataset never holds a second copy of it.

| Table | Its protected columns |
| --- | --- |
| `<ctx>_<agg>_state_flat` | Each flat column whose state property is `@Sensitive`, masked as the state masks it |
| `<ctx>_<agg>_state` | The same flat columns. The `state` and `state_numbers` columns, documents holding the protected values |
| `<ctx>_<agg>_state_events`, `<ctx>_<agg>_events` | The `state`, `state_numbers`, `events` and `payload` columns, documents holding the protected values |

- **A declaration only tightens.** A field may declare `sensitive`: at a higher level, or with a mask that keeps less of the value, it is masked as declared. A declaration looser than its source, a lower level or a mask that keeps more, is refused, and the node does not start; the message names the state's path. A service's dataset has no approver, so loosening a protection is a change to the state's `@Sensitive`, reviewed with the code. A dataset that must lower one belongs in the [analytics catalog](../data/datasets#sources).
- **A document column is not read.** A field masks a column whole, never one value inside it, so a dataset that reads a document column holding a protected value is refused. Read the value from its own column, as `_state_flat` holds it.
- **A table of no state needs `"lineage": "none"`.** Wow cannot tell what an operator's own view, a join's result, another service's table or a table of no Wow service holds. A dataset over one states `"lineage": "none"`, its author's assertion that the table holds no value a state marks `@Sensitive`, and is masked only where it declares `sensitive`. Without it the dataset is served unavailable: its queries are answered `409 wow.query.dataset-unavailable`, and the node logs why when it starts.

## Rows of an aggregate with an access policy

A dataset whose rows are each of one aggregate names that aggregate in its scope, with the column holding each row's aggregate id:

<!-- sample: fragment -->

```json
"scope": { "tenant": "tenant_id", "access": { "aggregate": "shop.order", "id": "order_id" } }
```

When the aggregate has an access policy ([Write an access policy](./ownership#write-an-access-policy)), the policy's `read` decides the rows, in place of the owner and space columns:

- **By what the aggregate is now.** A row shows exactly when the caller may read its aggregate as it is now, whatever the row recorded when it was written. On ClickHouse these are the attributes of the aggregate's newest commit in the sink's `<context>_<aggregate>_state`, in the database the sink writes to, so a transfer or a changed attribute shows in the dataset once the sink has written that commit. An attribute a later version of the service declares gets its column on `_state` (`access_<name>`) when that version's sink starts, but each row keeps the attributes it was written with, so it has no value in an aggregate's newest row until the aggregate's next commit; to read it at once, reset the sink's subscription of the aggregate, `wow.clickhouse-sink.<aggregate>`, which writes every commit again with the attributes the log holds now ([Rebuild the warehouse](../operate/clickhouse#rebuild-the-warehouse)).
- **All of an aggregate's rows or none.** The policy's whole predicate is decided on the aggregate, never on a row's own columns, and every operation of the dataset (a list, a page, a count, an aggregation) reads the same rows.
- **No attributes, no rows.** A row is hidden from every caller the policy restricts when the warehouse holds no attributes of its aggregate: an aggregate the sink has not written yet. A policy that grants a caller every aggregate reads every row.
- **Services and trusted code.** A service reading as itself and trusted in-process code are read by no access policy, bounded by the tenant, as for any query.

The id column is a `String` column, and the aggregate one of the service's: a dataset bound to an aggregate the service does not have is refused. An aggregate without an access policy leaves the rows to the owner and space columns, as for any dataset.

## Where the rows come from

Datasets are served when a dataset backend is wired; in 10.0, ClickHouse's: `wow-clickhouse` on the class path, with `wow.clickhouse.endpoint` set or a ClickHouse `Client` bean. The ClickHouse sink, switched on by `wow.clickhouse.sink.enabled: true`, writes each published aggregate's commits to tables a dataset reads, such as `<context>_<aggregate>_state_flat`, the current states with the state's scalar fields as columns ([ClickHouse](../operate/clickhouse)).

## Query it

| Request | Answers |
| --- | --- |
| `POST /wow/dataset/<context>/<dataset>/query` | A query of the dataset |
| `GET /wow/dataset/<context>/<dataset>/query` | Its capability descriptor |
| `GET /wow/dataset` | The datasets the caller sees |

The body is a query of the [Query Language](../reference/query-language): a buyer's sales by status, say, is an `aggregate` grouped by `status` with a sum of `totalAmount`. Each caller reads only the rows of its own tenant, owner and space. In Kotlin, `QueryApi.of(caller).datasets(DatasetName.of(context, dataset), rowType)` runs the same queries; like the aggregation it serves, it is experimental (`@OptIn(ExperimentalWowApi::class)`).

`wow.query.dataset.max-concurrent` (8) bounds how many dataset queries a node runs at once, under a bulkhead of their own, so analytics never take the warehouse the sink is writing to; a query beyond it waits briefly, then answers `429 wow.query.busy`. A dataset that is served but cannot be queried now answers `409 wow.query.dataset-unavailable`, and an unknown one `404 wow.query.dataset-not-found`.

## Datasets across services

A dataset that joins the tables of several services, or that analysts define and review without a release, belongs to the analytics catalog, which publishes each version as a ClickHouse view that every node serves the same way ([Platform Services](../migrate/platform)).
