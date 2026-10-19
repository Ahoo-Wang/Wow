# Dataset registry (V10)

**Incubating**, unpublished (decision D88): the V10 dataset registry service, roadmap 9.3b (decisions D238, D241), in
place of the 9.x `dataset-registry/`, which stays until the 9.x cleanup (9.3c).

The data-source catalog is the warehouse's own metadata: every table or view of the configured databases is one source,
its id `<connection>:<database>:<table>`. A table the Wow ClickHouse Sink keeps is described by its `wow_catalog` row,
adopted only from a trusted writer and only while the table's uuid and columns match; an analyst's table is drafted
from `system.columns` and reviewed. Wide tables (compile, publish) follow in 7.2b-2, their distribution and query in 7.2c.

| Module | Contents |
| --- | --- |
| `dataset-registry-api` | Context `dataset-registry`, the `source` aggregate's commands, events and values |
| `dataset-registry-domain` | Aggregate `Source`, the `WarehouseCatalog`, `TrustedWriters` and `KnownAggregates` ports, the trust rules |
| `dataset-registry-server` | The service: the ClickHouse catalog, the watcher that syncs changed tables, roles and routes |

## Commands

| Command | Who | Effect |
| --- | --- | --- |
| `DraftSource` | admin | Drafts an analyst's table from its columns; refused for a table with a trusted `wow_catalog` row |
| `ReviseSource` | admin | Declares an analyst's tenant (required), owner and space columns and its references to known aggregates |
| `ReviewSourceSensitivity` | admin | Records each value's protection; a Wow source's values are only tightened |
| `MarkSourceReviewed` | admin | Draft → reviewed, once the tenant column is declared and every string column reviewed |
| `SyncSource` | the watcher, admin | Reads the table and its `wow_catalog` rows again; adopts a trusted row |
| `ApproveSensitivityChange` | approver | Accepts the lower protection a Wow source's row now gives the pending values |

Roles: `dataset-registry.admin`, `dataset-registry.approver`, `dataset-registry.viewer` (reads the sources).

## Run

```bash
./gradlew :dataset-registry-server:run --args='--wow.clickhouse.endpoint=http://localhost:8123 --wow.dataset-registry.trusted-writers.default=shop'
```

`wow.dataset-registry.*`: `connection` (`default`), `databases` (`wow.clickhouse.database` when empty),
`trusted-writers.<account>` (the contexts it is trusted for), `refresh-interval` (30s), `watch` (on).
