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
  "scope": {
    "tenant": "tenant_id",
    "owner": "owner_id",
    "tags": { "region": "region", "dept": "depts" }
  },
  "storage": { "table": "bi_db.order_wide", "deduplicate": "FINAL" },
  "fields": {
    "order_id": { "types": ["STRING"], "nullable": false },
    "tenant_id": { "types": ["STRING"], "nullable": false },
    "owner_id": { "types": ["STRING"], "nullable": false },
    "city": { "types": ["STRING"], "nullable": false },
    "region": { "types": ["STRING"], "nullable": false },
    "depts": {
      "kind": "ARRAY",
      "items": { "types": ["STRING"] },
      "nullable": false
    },
    "amount": { "types": ["DECIMAL"], "nullable": false },
    "updated_at": {
      "types": ["STRING"],
      "semantic": { "type": "TEMPORAL_DATE" }
    },
    "customer_phone": {
      "types": ["STRING"],
      "sensitive": { "level": "CONFIDENTIAL", "keepSuffix": 4 }
    }
  }
}
```

| Key                          | Meaning                                                                                                                                                                                                                                                                              |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `fields`                     | The queryable fields, each a column of the table, in the [Query Model Schema](./query-model-schema.md) declaration vocabulary. A column that is not declared is never exposed                                                                                                        |
| `scope.tenant`               | The tenant column: the caller scope's `tenantId` lands on it, and `wow.query.require-authenticated-scope` requires it. Exactly one of it and `scope.platform`                                                                                                                        |
| `scope.platform`             | `true` for a table without a tenant column: the dataset belongs to the platform tenant `(0)`, so a caller of tenant `(0)` reads every record and a caller of any other tenant reads none; it is never open to every tenant. For an enterprise warehouse's tables that have no tenant |
| `scope.owner`, `scope.space` | Optional. The owner and space columns                                                                                                                                                                                                                                                |
| `scope.tags`                 | Optional. ABAC tag keys bound to columns; see [ABAC](#abac)                                                                                                                                                                                                                          |
| `identity`                   | Optional. Each row's unique identity: ID filters, cursor pages and the View Engine record view's `rowKey` need it                                                                                                                                                                    |
| `eventTime`                  | Optional. The default `orderBy` of `FIRST`/`LAST` metrics                                                                                                                                                                                                                            |
| `fields.*.sensitive`         | The field's sensitivity level and mask (`level`, `keepPrefix`, `keepSuffix`), as `@Sensitive` means them. A wide table's columns have no type to carry the annotation, so a dataset declaration is the exception                                                                     |
| `storage`                    | The query backend's own binding, passed to it unread; see [ClickHouse Backend](#clickhouse)                                                                                                                                                                                          |

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
      endpoints: [http://clickhouse:8123]
      username: wow_reader
      password: ${CLICKHOUSE_PASSWORD}
      database: bi_db
```

A dataset's `storage` names its `table` (`database.table`, or a table in its source's default database), optionally `deduplicate`: `NONE` (the default) or `FINAL`, and optionally `source`. `FINAL` merges a Replacing or Collapsing MergeTree's rows at read time, so reads are final-consistent.

When the Catalog compiles a dataset it checks the table against the declaration through `system.columns`; any mismatch fails the compile, and the Catalog keeps the previous version:

- every declared field is a column of the table whose type family matches the declaration (string-like, integer, `Float`/`Decimal`, `Bool`, date);
- a date column is declared `TEMPORAL_DATE`; a field declared non-null cannot be a `Nullable` column;
- the scope, tag and identity columns are not `Nullable`, and the scope and identity columns are not arrays;
- `FINAL` needs an engine that merges rows;
- the only array is `Array(String)` (of `LowCardinality(String)` or `FixedString` too), declared as an array of strings; `Map`, `Tuple` and other arrays are not supported yet.

The connection settings under `wow.query.clickhouse` are the default source; `wow.query.clickhouse.sources.{name}` configures other named sources (`endpoints`, `username`, `password`, `database`, `connection-timeout`, `socket-timeout`, `max-connections`), each over its own connection. A dataset's `storage.source` names one; without it the default source serves the dataset:

```yaml
wow:
  query:
    clickhouse:
      sources:
        warehouse:
          endpoints: [http://dw:8123]
          username: wow_reader
          password: ${DW_PASSWORD}
          database: dw
```

Each entry's queries have limits, sent as server settings; an over-limit query fails rather than returning a truncated result. HTTP queries default to 30 seconds and 4 GiB of memory (`wow.query.clickhouse.http.*`); in-process queries are unlimited by default (`wow.query.clickhouse.in-process.*`). When a subscription is cancelled, the backend stops the query on ClickHouse.

Give Wow a read-only account (`readonly=2`). The security boundary is Wow's admission, not ClickHouse row policies.

## HTTP Routes

| Method | Path                                       | Body and response                                                                                                           |
| ------ | ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------- |
| `GET`  | `/dataset`                                 | The directory: each served dataset's context, name, title and descriptor version (`null` while its schema does not compile) |
| `GET`  | `/dataset/{context}/{dataset}/schema`      | The capability descriptor (ETag, 304 while unchanged)                                                                       |
| `POST` | `/dataset/{context}/{dataset}/count`       | `FilterExpression` → the record count                                                                                       |
| `POST` | `/dataset/{context}/{dataset}/list`        | `ListQuery` → rows (a JSON array or SSE)                                                                                    |
| `POST` | `/dataset/{context}/{dataset}/paged`       | `PagedQuery` → `{ total, list }`                                                                                            |
| `POST` | `/dataset/{context}/{dataset}/cursor`      | `CursorQuery` → `{ list, nextCursor }`                                                                                      |
| `POST` | `/dataset/{context}/{dataset}/single`      | `SingleQuery` → one row, 404 when none                                                                                      |
| `POST` | `/dataset/{context}/{dataset}/aggregation` | `AggregationQuery` → rows (a JSON array or SSE)                                                                             |

These routes are registered when a dataset query backend (ClickHouse, say) is configured. Every route but the directory is also served under `/tenant/{tenantId}`. These routes run the same pipeline as an aggregate's query routes: the HTTP entry, the HTTP budget, the idle timeout, the error codes and the capability descriptor. A dataset the service does not serve answers 404.

The caller scope comes from the `{tenantId}` path variable (else `Command-Tenant-Id`), `Command-Owner-Id` and `Wow-Space-Id`; the last two apply only when the dataset declares the matching column. They all come from the request, so the scope is **declared**; when a trusted gateway (CoSec, say) authenticates them, register your own `DatasetQueryRequestScope` that marks them authenticated. A `ScopeContributor` applies to aggregate routes by default: a dataset's columns are not an aggregate's, so a contributor restricts datasets only when it overrides `contribute(dataset, request)`.

## Adding and Removing Datasets at Run Time

The `QueryDatasets` bean holds the datasets this service serves: the declarations of `wow.query.datasets` at startup, and whatever is `add`ed, `replace`d or `remove`d at run time. A change takes effect at once: the Catalog compiles the new declaration, and the gateways, routes and directory follow. `add` is refused, changing nothing, for a name an aggregate of the context already takes, or when the service has no dataset query backend.

## Analysing a Dataset in the View Engine

A dataset is published at run time, so the view engine takes no definition written for it: the definition is derived from its capability descriptor (`defineDataset`) and registered with the engine at run time (`engine.register`). Every field of the record the descriptor lists appears, labelled by its `description`, else its name:

```typescript
import { DatasetQueryClient } from '@ahoo-wang/wow-client';
import { defineDataset, type ViewEngine } from '@ahoo-wang/wow-view-engine';

async function registerDataset(
  engine: ViewEngine,
  context: string,
  dataset: string,
  title: string,
): Promise<void> {
  const client = new DatasetQueryClient({ context, dataset });
  const result = await client.describe();
  if (result.notModified) return;
  const id = `${context}.${dataset}`;
  engine.register({
    definition: defineDataset(result.descriptor, { id, source: id, title }),
    source: client,
  });
}
```

Registering a dataset again replaces it whole; views already open keep the definition they opened on until reopened, and `engine.unregister(id)` removes one. Opening a saved view of a dataset not registered yet reports the definition as not found.

`DatasetResources` does this for a host: it reads the directory (`DatasetDirectoryClient`), registers, registers again and removes by version, and answers the datasets served. The host binds a route to each, so they appear in its navigation, and opening one is how a reader starts analysing it:

```typescript
import {
  DatasetDirectoryClient,
  DatasetQueryClient,
} from '@ahoo-wang/wow-client';
import { DatasetResources, type ViewEngine } from '@ahoo-wang/wow-view-engine';
import { bind, type ViewBinding } from '@ahoo-wang/wow-view-engine/ui';

async function datasetBindings(engine: ViewEngine): Promise<ViewBinding[]> {
  const directory = new DatasetDirectoryClient();
  const datasets = new DatasetResources(engine, {
    list: () => directory.list(),
    source: entry =>
      new DatasetQueryClient({
        context: entry.contextName,
        dataset: entry.name,
      }),
  });
  const served = await datasets.refresh();
  return served.map(({ id }) =>
    bind(id, { route: view => `/datasets/${id}${view ? `/${view}` : ''}` }),
  );
}
```

A dataset's title, record noun and field labels (wording that only renames and narrows, D86) can be kept in the view store by a tenant's administrators rather than in the host's code. `WowDatasetWordings` from `@ahoo-wang/wow-view-store` reads and writes it; hand it to `DatasetResources` as the datasets' store of wording, read before each `refresh` and registered in, the wording read last standing when it cannot be read:

```typescript
import { WowDatasetWordings } from '@ahoo-wang/wow-view-store';

const datasets = new DatasetResources(engine, {
  list: () => directory.list(),
  source: entry => new DatasetQueryClient({ context: entry.contextName, dataset: entry.name }),
  wordings: new WowDatasetWordings({ fetcher }), // the fetcher WowViewStore uses
});
```

Where the host grants `editDatasetWording`, the view manager gains a Wording tab: an administrator renames the dataset, its records and its fields, reorders and hides fields there, and a save registers the dataset again in the new words. The CoSec gateway decides who may write ([View Store](../extensions/view-store.md#dataset-wording)).

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

## ABAC {#abac}

`AbacQueryPolicy` also restricts a dataset that binds tag columns in `scope.tags`, with the rules of the Snapshot `tags` system field. Each tag key is bound to one column: a string column for a single-valued tag, an `Array(String)` column for a multi-valued one. An empty string or array is a record without the tag.

| Principal tag    | String column        | Array column                  |
| ---------------- | -------------------- | ----------------------------- |
| `["a", "b"]`     | `a` or `b`, or empty | contains `a` or `b`, or empty |
| Wildcard `["*"]` | not empty            | not empty                     |

- With `wow.query.abac.match-missing-tag-key` off, an empty record no longer matches as a public resource.
- A tag key the dataset binds no column for is a tag no record carries: unrestricted by default; with a wildcard, or with the option above off, nothing matches.
- A dataset without `scope.tags` is not restricted by `AbacQueryPolicy`, which resolves no principal tags for it.
- Tag columns are ordinary fields: callers may filter them by element (`IN`, `CONTAINS_ALL`, `IS_EMPTY`), but an array column is never sorted or aggregated.

## TypeScript Client

`@ahoo-wang/wow-client`'s `DatasetQueryClient` calls the routes above. It satisfies View Engine's `ViewSource`, `describe` included, so it serves Record, Analysis and Dashboard views as it is:

```typescript
import { DatasetQueryClient, aggregation } from '@ahoo-wang/wow-client';

const orders = new DatasetQueryClient({
  context: 'analytics',
  dataset: 'order_wide',
});
const byCity = await orders.aggregate(
  aggregation.query({
    groupBy: [aggregation.terms('city', 'city')],
    metrics: [aggregation.sum(aggregation.field('amount'), 'gmv')],
  }),
);
```

`resourceAttribution: ResourceAttributionPathSpec.TENANT` sends the requests under `/tenant/{tenantId}`, with `{tenantId}` filled from `urlParams`.

## Semantic Differences

- **Missing values**: a ClickHouse table's only missing value is `NULL` in a `Nullable` column. A non-`Nullable` column does not support presence operators such as `IS_NULL` and `EXISTS`, and admission rejects them; on a `Nullable` column `EXISTS` equals `IS_NOT_NULL`, so, as on Elasticsearch, an explicit `null` and a missing value cannot be told apart.
- **NE and NOT_IN** also match `NULL`, as on MongoDB.
- **DISTINCT_COUNT** is exact (`uniqExact`); **PERCENTILE** is a t-digest approximation, and the capability descriptor marks it `approximate`.
- **Array columns**: a scalar operand matches any element. An empty array is the missing value, so `IS_EMPTY`, `IS_NULL` and `NOT_EXISTS` all match it, as on Elasticsearch; whole-array equality is not supported.
- A single numeric field aggregates natively, so a `Decimal` sum keeps its precision; expressions are computed in `Float64`.
