---
title: Dataset Query
description: Query declared datasets such as ClickHouse wide tables through Wow — declaration, the ClickHouse backend, HTTP routes, governance and semantic differences.
---

# Dataset Query

An aggregate's Snapshot and EventStream answer questions about one aggregate. Questions across aggregates (orders × customers × products) are answered by **wide tables** that analysts build in ClickHouse; Wow declares a wide table as a **dataset** and serves it through the same admission, capability descriptor, scope, policies and masking. View Engine's Record, Analysis and Dashboard can then observe data across aggregates directly.

The join happens in the wide table, not in the query protocol: a query always targets one dataset, and the query language is the same AST as [Filter Expression](./filter-expression.md), [Data Query](./data-query.md) and [Aggregation Query](./aggregation-query.md). The design is `documentation/designs/2026-10-10-clickhouse-dataset-query-design.md`.

## Declaring a Dataset

A dataset is named within a bounded context, such as `analytics.order_wide`. Its declaration ships with the application in `META-INF/wow/dataset/{context}.{dataset}.json`; a `config/wow/dataset/{context}.{dataset}.json` in the working directory replaces it as a whole, so operators can adjust a dataset without a rebuild.

```json
{
  "title": "Order wide table",
  "identity": "order_id",
  "eventTime": "updated_at",
  "scope": { "tenant": "tenant_id", "owner": "owner_id" },
  "storage": { "table": "bi_db.order_wide", "deduplicate": "FINAL" },
  "fields": {
    "order_id": { "types": ["STRING"], "nullable": false },
    "tenant_id": { "types": ["STRING"], "nullable": false },
    "owner_id": { "types": ["STRING"], "nullable": false },
    "city": { "types": ["STRING"], "nullable": false },
    "amount": { "types": ["DECIMAL"], "nullable": false },
    "updated_at": { "types": ["STRING"], "semantic": { "type": "TEMPORAL_DATE" } },
    "customer_phone": { "types": ["STRING"], "sensitive": { "level": "CONFIDENTIAL", "keepSuffix": 4 } }
  }
}
```

| Key | Meaning |
| --- | --- |
| `fields` | The queryable fields, each a column of the table, in the [Query Model Schema](./query-model-schema.md) declaration vocabulary. A column that is not declared is never exposed |
| `scope.tenant` | Required. The tenant column: the caller scope's `tenantId` lands on it, and `wow.query.require-authenticated-scope` requires it |
| `scope.owner`, `scope.space` | Optional. The owner and space columns |
| `identity` | Optional. Each row's unique identity: ID filters, cursor pages and the View Engine record view's `rowKey` need it |
| `eventTime` | Optional. The default `orderBy` of `FIRST`/`LAST` metrics |
| `fields.*.sensitive` | The field's sensitivity level and mask (`level`, `keepPrefix`, `keepSuffix`), as `@Sensitive` means them. A wide table's columns have no type to carry the annotation, so a dataset declaration is the exception |
| `storage` | The query backend's own binding, passed to it unread; see [ClickHouse Backend](#clickhouse) |

List the datasets a service serves in `wow.query.datasets`:

```yaml
wow:
  query:
    datasets:
      - analytics.order_wide
```

A dataset takes its context's name slot: within one context, a dataset cannot share its name with an aggregate, or startup fails. A `QueryPolicy` keyed by an aggregate's name therefore never applies to a dataset. A dataset's model is `DATASET`; a policy can read `context.schema.model` to tell it apart.

## ClickHouse Backend {#clickhouse}

Add the `clickhouse-support` capability and enable ClickHouse:

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.wow:clickhouse-support") }
    }
}
```

```yaml
wow:
  query:
    clickhouse:
      enabled: true
      endpoints: [ http://clickhouse:8123 ]
      username: wow_reader
      password: ${CLICKHOUSE_PASSWORD}
      database: bi_db
```

A dataset's `storage` names its `table` (`database.table`, or a table in `wow.query.clickhouse.database`) and optionally `deduplicate`: `NONE` (the default) or `FINAL`. `FINAL` merges a Replacing or Collapsing MergeTree's rows at read time, so reads are final-consistent.

When the Catalog compiles a dataset it checks the table against the declaration through `system.columns`; any mismatch fails the compile, and the Catalog keeps the previous version:

- every declared field is a column of the table whose type family matches the declaration (string-like, integer, `Float`/`Decimal`, `Bool`, date);
- a date column is declared `TEMPORAL_DATE`; a field declared non-null cannot be a `Nullable` column;
- the scope and identity columns are not `Nullable`;
- `FINAL` needs an engine that merges rows;
- `Array`, `Map`, `Tuple` and similar columns are not supported yet.

Each entry's queries have limits, sent as server settings; an over-limit query fails rather than returning a truncated result. HTTP queries default to 30 seconds and 4 GiB of memory (`wow.query.clickhouse.http.*`); in-process queries are unlimited by default (`wow.query.clickhouse.in-process.*`). When a subscription is cancelled, the backend stops the query on ClickHouse.

Give Wow a read-only account (`readonly=2`). The security boundary is Wow's admission, not ClickHouse row policies.

## HTTP Routes

| Method | Path | Body and response |
| --- | --- | --- |
| `GET` | `/dataset/{context}/{dataset}/schema` | The capability descriptor (ETag, 304 while unchanged) |
| `POST` | `/dataset/{context}/{dataset}/count` | `FilterExpression` → the record count |
| `POST` | `/dataset/{context}/{dataset}/list` | `ListQuery` → rows (a JSON array or SSE) |
| `POST` | `/dataset/{context}/{dataset}/paged` | `PagedQuery` → `{ total, list }` |
| `POST` | `/dataset/{context}/{dataset}/cursor` | `CursorQuery` → `{ list, nextCursor }` |
| `POST` | `/dataset/{context}/{dataset}/single` | `SingleQuery` → one row, 404 when none |
| `POST` | `/dataset/{context}/{dataset}/aggregation` | `AggregationQuery` → rows (a JSON array or SSE) |

Every route is also served under `/tenant/{tenantId}`. These routes run the same pipeline as an aggregate's query routes: the HTTP entry, the HTTP budget, the idle timeout, the error codes and the capability descriptor. A dataset the service does not serve answers 404.

The caller scope comes from the `{tenantId}` path variable (else `Command-Tenant-Id`), `Command-Owner-Id` and `Wow-Space-Id`; the last two apply only when the dataset declares the matching column. They all come from the request, so the scope is **declared**; when a trusted gateway (CoSec, say) authenticates them, register your own `DatasetQueryRequestScope` that marks them authenticated. The `ScopeContributor`s of the aggregate routes do not apply to dataset routes.

## In-Process Queries

The `DatasetQueryGateways` bean holds each dataset's `DatasetQueryGateway` by name. It has the same ten methods as an aggregate's query gateway, and its records are `ObjectNode`s:

```kotlin
val orders = datasetQueryGateways[MaterializedNamedAggregate("analytics", "order_wide")]!!
orders.aggregate(
    aggregation {
        filter { "tenant_id" eq tenantId }
        terms("city", "city")
        sum("amount", "gmv")
        sort { "gmv".desc() }
        limit(10)
    },
)
```

`QueryFilter`s and `QueryPolicy`s apply to dataset queries too; a filter meant only for datasets implements `DatasetQueryFilter`.

## Semantic Differences

- **Missing values**: a ClickHouse table's only missing value is `NULL` in a `Nullable` column. A non-`Nullable` column does not support presence operators such as `IS_NULL` and `EXISTS`, and admission rejects them; on a `Nullable` column `EXISTS` equals `IS_NOT_NULL`, so, as on Elasticsearch, an explicit `null` and a missing value cannot be told apart.
- **NE and NOT_IN** also match `NULL`, as on MongoDB.
- **DISTINCT_COUNT** is exact (`uniqExact`); **PERCENTILE** is a t-digest approximation, and the capability descriptor marks it `approximate`.
- **ABAC**: `AbacQueryPolicy` applies to Snapshot only; datasets, like event streams, are not restricted by it.
- A single numeric field aggregates natively, so a `Decimal` sum keeps its precision; expressions are computed in `Float64`.
