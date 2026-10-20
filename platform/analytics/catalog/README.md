# Analytics catalog (V10)

**Incubating**, unpublished (decision D88): the V10 analytics catalog service, roadmap 9.3b (decisions D238, D241, D251):
the control plane of the analytics platform, in place of the 9.x `dataset-registry/`, which stays until the 9.x cleanup
(9.3c). The data plane (the Sink's writes, the dataset queries) never calls it on its own path.

The data-source catalog is the warehouse's own metadata: every table or view of the configured databases is one source,
its id `<connection>:<database>:<table>`; the connection has an engine type (`clickhouse`, the one 10.0 implements). A table the Wow ClickHouse Sink keeps is described by its `wow_catalog` row,
adopted only from a trusted writer and only while the table's uuid and columns match; an analyst's table is drafted
from `system.columns` and reviewed. A dataset is a wide table over the sources (D259 7.2b-2): its definition names the
sources under aliases, the joins that bring them in (each keyed on the tenant, unless the joined source is a platform
dimension an approver reviewed), the columns (a source's value, `<alias>.<column>[.<path>]`, or an expression of the
functions the catalog allows), the filters and the subject, whose scope the rows take; each column's protection
follows its lineage. Its compile and publication follow in 7.2b-2, its distribution and query in 7.2c.

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

Roles (D251 ②): `analytics.catalog.admin` (connections, trusted writers), `analytics.catalog.approver` (reviews and
sensitivity changes), `analytics.catalog.editor` (an analyst's own sources), `analytics.catalog.viewer` (reads).

## Run

```bash
./gradlew :analytics-catalog-server:run --args='--wow.clickhouse.endpoint=http://localhost:8123 --wow.analytics.catalog.trusted-writers.default=shop'
```

`wow.analytics.catalog.*`: `connection` (`default`), `engine` (`clickhouse`), `databases` (`wow.clickhouse.database` when empty),
`trusted-writers.<account>` (the contexts it is trusted for), `refresh-interval` (30s), `watch` (on).
