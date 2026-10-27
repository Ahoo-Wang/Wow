# Analytics catalog

**Incubating**, unpublished: the analytics catalog service,
the control plane of the analytics platform, in place of the 9.x `dataset-registry/`, which stays until the 9.x cleanup. The data plane (the Sink's writes, the dataset queries) never calls it on its own path.

The data-source catalog is the warehouse's own metadata: every table or view of the configured databases is one source,
its id `<connection>:<database>:<table>`; the connection has an engine type (`clickhouse`, the one 10.0 implements). A table the Wow ClickHouse Sink keeps is described by its `wow_catalog` row,
adopted only from a trusted writer and only while the table's uuid and columns match; an analyst's table is drafted
from `system.columns` and reviewed. A dataset is a wide table over the sources: its definition names the
sources under aliases, the joins that bring them in (each keyed on the tenant, unless the joined source is a platform
dimension an approver reviewed), the columns (a source's value, `<alias>.<column>[.<path>]`, or an expression of the
functions the catalog allows), the filters and the subject, whose scope the rows take; each column's protection
follows its lineage. Each version compiles to one ClickHouse view, `ds_<context>_<name>_v<N>` (`DatasetCompiler`, a
pure function: a source whose engine merges rows read through `FINAL`, a left join's empty side `NULL`, a value inside
JSON text through `JSONExtractString`, every name quoted and every text escaped). Publishing creates the version's view
in `wow_datasets` (the only DDL the catalog runs); every node serving queries reads the publications from the datasets'
snapshots (`DatasetPublicationWatcher`: what changed every 5 seconds, everything every 5 minutes) and serves the current
version under `/wow/dataset/<context>/<name>/query`, each value masked as its sources say. Those queries are the data
plane's: they need no catalog role, only the node's query policies (each caller reads its own tenant's, owner's and
space's rows; ABAC), and run under the dataset bulkhead (`wow.query.dataset.max-concurrent`, 8). A column whose source
declares it a reference (or a Wow table's `aggregate_id`) is declared `REFERENCE` to that aggregate, so a row's action
goes to the service that owns it. A retired dataset's views are
dropped 7 days later. A change of a source recompiles the datasets that read it (`DatasetRecompilation`): what compiles
with the same columns (names and types) is published as the next version (stricter masking, another rendering); anything
else (no longer compiling, columns that would change, a new approval) marks the dataset `NEEDS_REVISION`. Its published
version goes on serving while its view still gives the columns it was published with; once it does not, queries are
answered 409 `wow.query.dataset-unavailable` ("needs revision"; the problems are in the dataset's state and the log)
until a revision is published or its sources come back.

| Module | Contents |
| --- | --- |
| `analytics-catalog-api` | Context `analytics-catalog`, the `source` and `dataset` aggregates' commands, events and values |
| `analytics-catalog-domain` | Aggregates `Source` and `Dataset`, the `WarehouseCatalog`, `TrustedWriters`, `KnownAggregates` and `DatasetSources` ports, the trust rules, the definitions' checks and lineage |
| `analytics-catalog-server` | The service: the ClickHouse catalog, the watcher that syncs changed tables, roles and routes |

## Commands

| Command | Who | Effect |
| --- | --- | --- |
| `DraftSource` | editor, admin | Drafts an analyst's table from its columns; refused for a table with a trusted `wow_catalog` row |
| `ReviseSource` | editor, admin | Declares an analyst's tenant, owner and space columns and its references to known aggregates, or that it is a platform dimension |
| `ReviewSourceSensitivity` | editor, admin | Records each value's protection; a Wow source's values are only tightened |
| `MarkSourceReviewed` | approver, admin | Draft → reviewed, once the tenant column (or the platform dimension) is declared and every string column reviewed |
| `SyncSource` | the watcher, admin | Reads the table and its `wow_catalog` rows again; adopts a trusted row |
| `ApproveSensitivityChange` | approver | Accepts the lower protection a Wow source's row now gives the pending values |
| `DraftDataset` | editor, admin | Drafts the dataset `<context>.<name>` from a definition checked against the catalog |
| `ReviseDataset` | editor, admin | Replaces the dataset's definition, checked again |
| `PublishDataset` | editor, admin | Compiles the definition as the next version and creates its view; queries then read it |
| `RollbackDataset` | editor, admin | Switches queries back to an earlier version, its view created again if gone |
| `RetireDataset` | editor, admin | Stops the queries; the views are dropped after the retention (`DropDatasetViews`, scheduled) |
| `ApproveDatasetPublication`, `RefuseDatasetPublication` | approver | Decides a publication that takes an approver: every one with `approval.publications`, and any computing from sensitive values or lowering a protection |
| `ApproveSource` | approver | With `approval.sources`, lets the datasets read a reviewed or adopted source |
| `RecompileDataset` | the catalog, admin | Compiles a published dataset again after a source changed |

Roles: `analytics.catalog.admin` (connections, trusted writers), `analytics.catalog.approver` (reviews and
sensitivity changes), `analytics.catalog.editor` (an analyst's own sources), `analytics.catalog.viewer` (reads).

## Run

```bash
./gradlew :analytics-catalog-server:run --args='--wow.clickhouse.endpoint=http://localhost:8123 --wow.analytics.catalog.trusted-writers.default=shop'
```

`wow.analytics.catalog.*`: `connection` (`default`), `engine` (`clickhouse`), `databases` (`wow.clickhouse.database` when empty),
`trusted-writers.<account>` (the contexts it is trusted for), `refresh-interval` (30s), `watch` (on).
`wow.analytics.catalog.datasets.*`: `database` (`wow_datasets`; the account needs `CREATE DATABASE`, `CREATE VIEW` and
`DROP VIEW` there), `retention` (7d), `serve` (on), `refresh-interval` (5s), `reconcile-interval` (5m). The approval switches,
`approval.sources` and `approval.publications`, are off by default. A node that serves
the datasets is ready (`/actuator/health/readiness`) once it read the publications; its metrics are
`wow.analytics.catalog.datasets.sync.age` and `wow.analytics.catalog.datasets.publication.lag`.
