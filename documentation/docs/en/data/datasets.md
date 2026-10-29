---
title: Datasets and the Analytics Catalog
description: Define a dataset over the warehouse's tables in the analytics catalog, review its sources and their protection, publish it as a version, and keep it serving when its sources change.
---

# Datasets and the Analytics Catalog

A dataset is a table for analysis, queried like a snapshot model. A service declares the datasets over its own tables in a file ([Publish Datasets from a Service](../guide/datasets)). A dataset that joins tables, those of several services, or an analyst's own, is defined in the analytics catalog instead: a service of its own, where analysts draft definitions, approvers review them, and every published version becomes a view every node serves. The catalog is incubating in 10.0, on ClickHouse.

The catalog is the control plane only. A dataset query never calls it: each node reads the published versions and answers the queries itself.

## Sources

Every table and view of the warehouse's configured databases is a source, its id `<connection>:<database>:<table>`. Two kinds:

- **A table the ClickHouse sink keeps** describes itself in its `wow_catalog` row: the aggregate it holds, its tenant, owner and space columns, and the protection of each value. The catalog adopts that row only from a writer trusted for the context (`wow.analytics.catalog.trusted-writers.<account>`), and only while the table's identity and columns still match it.
- **An analyst's table** is drafted from its columns (`DraftSource`) and reviewed: its tenant, owner and space columns and its references to known aggregates are declared (`ReviseSource`), or it is declared a platform dimension every tenant shares, such as regions; every string column's protection is recorded (`ReviewSourceSensitivity`); and an approver marks it reviewed (`MarkSourceReviewed`).

A Wow table's protection can only be tightened in the catalog. When the sink's row lowers it, an approver accepts the change (`ApproveSensitivityChange`) before the datasets see it.

## Define a dataset

A definition names the sources under aliases, the joins that bring them in, the columns, the filters and the subject, the source whose tenant, owner and space each row takes. Orders with their customers' names:

<!-- sample: fragment -->

```json
{
  "context": "shop",
  "name": "order_customers",
  "definition": {
    "sources": [
      { "alias": "o", "sourceId": "default:wow:shop_order_state" },
      { "alias": "c", "sourceId": "default:crm:customers" }
    ],
    "subject": "o",
    "joins": [
      {
        "alias": "c",
        "kind": "LEFT",
        "on": [
          { "left": "o.tenant_id", "right": "c.tenant" },
          { "left": "o.state.customerId", "right": "c.id" }
        ]
      }
    ],
    "columns": [
      { "name": "order_id", "from": "o.id" },
      { "name": "customer", "from": "c.name" },
      { "name": "month", "expression": "toStartOfMonth(o.created_at)" }
    ],
    "filters": ["o.state.status != 'CANCELLED'"],
    "identity": "order_id",
    "eventTime": "month"
  }
}
```

posted as `DraftDataset` to `POST /analytics-catalog/dataset/draft-dataset`; the dataset's id is `<context>.<name>`.

- **Joins** are keyed on the tenant, so no row joins another tenant's, unless the joined source is a platform dimension an approver reviewed. `kind` is `INNER` by default; a `LEFT` join's missing side is `null`.
- **Columns** take a source's value, `<alias>.<column>`, or a value inside a JSON column, `<alias>.<column>.<path>`, or an `expression`. An expression is a small language of its own: references, literals, arithmetic and comparisons, `and`, `or`, `not`, `in`, `between`, `is null`, and calls of a fixed set of ClickHouse functions, conversions, dates, text, conditions and arithmetic of one row's values. Nothing that reads other rows, other tables or the server can be called. `filters` are expressions too, read as a query's filters read.
- **Protection** follows lineage: a column computed from a sensitive value is protected as that value is, and a column may declare a protection of its own (`sensitivity`); one that lowers its lineage's needs an approver to publish.
- `identity` names the column that identifies a row, and `eventTime` the column a relative-time filter reads by default.

The catalog checks a definition against the catalog when it is drafted or revised (`ReviseDataset`), and refuses one that names an unknown source or column, joins across tenants, or calls a function outside the set.

## Publish and version

`PublishDataset` compiles the definition into the next version, a ClickHouse view named `ds_<context>_<name>_v<N>` in the datasets' database (`wow_datasets`), which is the only DDL the catalog runs. Every node that serves queries reads the publications, what changed every 5 seconds and everything every 5 minutes, and from then on answers `POST /wow/dataset/<context>/<name>/query` from that version, each value masked as its sources say. A node is ready only once it has read them.

| Command | Does |
| --- | --- |
| `PublishDataset` | Publishes the definition as the next version |
| `ApproveDatasetPublication`, `RefuseDatasetPublication` | Decide a publication that needs an approver |
| `RollbackDataset` | Serves an earlier version again, its view created again if it was dropped |
| `RetireDataset` | Stops the queries; the views are dropped after the retention, 7 days by default, and publishing again before then brings the dataset back |

A publication needs an approver when it computes from sensitive values or lowers a protection, and every publication does with `wow.analytics.catalog.require-publication-approval`; `require-source-approval` makes every source wait for `ApproveSource` before a dataset reads it. Both switches are off by default.

## When a source changes

A change to a source recompiles every published dataset that reads it. A dataset that compiles to the same columns, the same names and types, is published as its next version by itself: a stricter mask, another rendering. Anything else marks it `NEEDS_REVISION`: a column that would change, a definition that no longer compiles, or a publication that would now need an approver.

A dataset that needs revision goes on serving its published version as long as that version's view still gives the columns it was published with. Once it no longer does, its queries are answered 409 `wow.query.dataset-unavailable` until a revision is published or the sources come back; the problems are recorded in the dataset's state.

## Query a dataset

A dataset is queried as a snapshot model is, with the same [query language](../reference/query-language) and the same capability descriptor, from HTTP, `QueryApi.datasets` in Kotlin, or the TypeScript `DatasetClient`. Each caller reads only the rows of its own tenant, owner and space, as the subject's columns say. A column that refers to an aggregate, a Wow table's `aggregate_id` or a source's declared reference, links each row to the service that owns that aggregate, so a view can act on it.

Dataset queries run under their own bulkhead, `wow.query.dataset.max-concurrent` (8) per node, so analyses never take the warehouse from the sink writing to it. A query beyond it waits briefly, then is answered 429 `wow.query.busy`. An unknown dataset is answered 404 `wow.query.dataset-not-found`.

## Roles

| Role | May |
| --- | --- |
| `analytics.catalog.admin` | Everything, including the connections and the trusted writers |
| `analytics.catalog.approver` | Review sources, accept protection changes, decide publications |
| `analytics.catalog.editor` | Draft and revise sources and datasets, publish, roll back and retire |
| `analytics.catalog.viewer` | Read the catalog |

Querying a dataset needs none of them, only the node's query policies. The catalog's configuration is under `wow.analytics.catalog.*`; its [README](https://github.com/Ahoo-Wang/Wow/tree/main/platform/analytics/catalog) lists every key. Moving 9.x's dataset registry to the catalog is part of [Platform Services](../migrate/platform).
