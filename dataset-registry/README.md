# Dataset registry

**Incubating**: built and tested with the other modules, not published yet; it is published with the analytics service
(dataset platform design R5).

Datasets as data: a data owner drafts a dataset from a table, revises its declaration, reviews the sensitivity of every
string field and publishes it; every instance that serves datasets serves the published versions. The design is
[2026-10-11-dataset-platform-design.md](../documentation/designs/2026-10-11-dataset-platform-design.md) (§4).

| Module | Contents |
| --- | --- |
| `wow-dataset-registry-api` | Bounded context `dataset-registry`, the `dataset` aggregate's commands and events, error codes |
| `wow-dataset-registry-domain` | Aggregate `Dataset`, its publication gate, and the ports a host implements |
| `wow-dataset-registry-starter` | Spring Boot auto-configuration: the projection that serves published datasets, the ClickHouse table inspector, the publication check |

## Commands

The aggregate id is the dataset's name, `{context}.{dataset}`; the aggregate has a static tenant.

| Command | Effect |
| --- | --- |
| `DraftDataset` | Drafts the declaration from a table's columns (`system.columns`), in the default or a named source: types, nullability, dates, string arrays; suggests the scope and identity |
| `ReviseDataset` | Replaces the draft declaration; a field whose declaration is unchanged keeps its review |
| `ReviewSensitivity` | Records, field by field, sensitive at a level or not sensitive |
| `PublishDataset` | Publishes the next version once the declaration is valid, every string field is reviewed, and it compiles against its table |
| `RetireDataset` | Stops serving the dataset; publishing again serves it again |

## Serving

`DatasetRegistryProjection` reads the published versions at startup and every `wow.dataset-registry.refresh-interval`
(30 seconds by default) and adds, replaces or removes them in `QueryDatasets`. A dataset of a declaration file
(`wow.query.datasets`) wins over a published one of the same name, which the registry refuses to publish.

## Security

Every command changes what the platform serves: only platform administrators should reach the routes under
`/dataset-registry/`, by a CoSec gateway rule.
