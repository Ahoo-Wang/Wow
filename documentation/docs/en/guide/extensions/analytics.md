---
title: Analytics Service
description: The ready-to-run analytics service wow-analytics-server — datasets on ClickHouse, the dataset registry, analysis views and boards, and its console — on an enterprise's own ClickHouse and CoSec, with no other BI platform to deploy.
---

# Analytics Service

`wow-analytics-server` is the dataset platform's deployable: it combines the [view store](./view-store.md), the dataset registry and [dataset queries](../query/dataset-query.md) on ClickHouse in one service with a console, behind CoSec. An enterprise deploys it on its own ClickHouse and the CoSec that issues its tokens, and has:

- **A dataset directory**: wide tables its analysts build are reviewed and published as datasets, which readers open in the console to analyse;
- **Analysis views and boards**: the view engine's workbenches and boards, kept in the service's own view store; shares, running totals, period-over-period comparisons and ranks within a group are computed by ClickHouse over every group ([Windows](../query/aggregation-query.md#windows));
- **Drafting, review and publication of datasets**: admins draft a declaration from a ClickHouse table, review each field's sensitivity, publish and retire it.

## Composition

| Dependency                     | Role                                                                            |
| ------------------------------ | ------------------------------------------------------------------------------- |
| `wow-view-store-starter`       | Views, boards and preferences                                                   |
| `wow-dataset-registry-starter` | Datasets' drafts, reviews, publication and directory                            |
| `wow-clickhouse`               | ClickHouse dataset queries and named sources (`wow.query.clickhouse.sources.*`) |
| `wow-mongo`                    | Events and snapshots of the view store's and the registry's aggregates          |
| `wow-kafka`                    | Command, event and state buses; a single instance may use the in-memory buses   |
| `wow-cosec`, CoSec 6.0         | Token verification, the principal's tenant and ABAC tags                        |

The registry's three modules, `wow-dataset-registry-api`, `-domain` and `-starter`, are published to Maven Central as well, to embed in any service that needs them.

## Security

The service needs no gateway in front of it; it is its own:

- **Verification**: each request's JWT is verified with `cosec.jwt.secret` (the shipped configuration reads `COSEC_JWT_SECRET`), which must be the key the enterprise's CoSec signs tokens with;
- **Routes**: the health probes, the console and the API documentation are public; everything else needs a valid token (401); the registry's commands need `wow.analytics.admin-role` (`analytics-admin` by default, else 403);
- **View store**: [the view store's gateway rules](./view-store.md#security-model) are enforced — the path's tenant is the token's and its owner the token's `sub`; the tenant reads its shared views and `wow.analytics.shared-view-role` writes them; only a platform-tenant principal with `wow.analytics.system-view-role` writes the system views; the server sets `CoSec-App-Id` to `wow.analytics.app-id`;
- **Datasets**: a query reads the tenant CoSec authenticated, not one the request declares; the ABAC tags are the principal's attributes prefixed `abac.` (`abac.region = "east,north"`), and a principal without tags is refused (`wow.query.abac.require-principal-tags`). See [Tags From CoSec Principal Attributes](../data-access.md#cosec-abac).

## Run It

The Docker image `ahoowang/wow-analytics-server` (also on GHCR and Aliyun):

```bash
docker run -p 8080:8080 -e COSEC_JWT_SECRET=<the enterprise CoSec signing key> -v $(pwd)/config:/opt/wow-analytics-server/config ahoowang/wow-analytics-server
```

The mounted `config/application.yaml` names MongoDB, Redis (CoSid's machine ids), Kafka and ClickHouse. To build the distribution from source:

```bash
pnpm --filter wow-analytics-console... build
```

```bash
./gradlew :wow-analytics-server:installDist
```

| Property                         | Default                                       | Meaning                                                              |
| -------------------------------- | --------------------------------------------- | -------------------------------------------------------------------- |
| `wow.analytics.admin-role`       | `analytics-admin`                             | The CoSec role that may change the dataset registry                  |
| `wow.analytics.shared-view-role` | `analytics-admin`                             | The role that may write the tenant's shared views                    |
| `wow.analytics.system-view-role` | `admin`                                       | The role a platform-tenant principal needs to write the system views |
| `wow.analytics.app-id`           | `analytics`                                   | The application the view store records                               |
| `wow.analytics.public-paths`     | The console's static files, the API docs      | Paths that need no token (the health probes are always public)       |
| `wow.analytics.console-paths`    | `/login`, `/datasets/**`, `/boards`, `/admin` | Page addresses answered with the console's `index.html`              |

## Console

The console reads the `cosec-token` CoSec front ends share (an enterprise single sign-on that stored it signs the console in directly), else takes a pasted access token. The home page lists the published datasets and the boards; opening a dataset starts analysing it in its workbench; a board's panels may read any dataset. Admins have a dataset management page too: draft from a table, revise the declaration (its scope's tenant column `scope.tenant` and tag columns `scope.tags`), review each field's sensitivity, publish and retire.
