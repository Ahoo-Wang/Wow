# Analytics service

`wow-analytics-server` is the dataset platform's deployable (R5): the view store, the dataset registry and the
datasets it publishes, served from ClickHouse, behind CoSec. An enterprise deploys it with its ClickHouse, MongoDB,
Redis, Kafka and the CoSec that issues its tokens, and has the dataset directory, analysis views and boards, and the
review and publication of datasets without another BI platform.

The design is [the analytics server design](../documentation/designs/2026-10-11-analytics-server-design.md).

| Path | Contents |
| --- | --- |
| `wow-analytics-server` | The Spring Boot service, its distribution (`config/application.yaml`, `ui/`) and Docker image |
| `console` | The console host (R5c), built into the distribution's `ui/` |
| `demo` | The service end to end on one machine: a retailer's wide table, published and analysed ([demo/README.md](demo/README.md)) |

## Security

The service is its own gateway: it verifies each request's JWT with `cosec.jwt.secret` (set `COSEC_JWT_SECRET`), and
enforces what a CoSec gateway would in front of the view store server:

- every path but the health probes, the console and the API documentation needs a verified token;
- the dataset registry's commands need `wow.analytics.admin-role` (`analytics-admin`);
- view store paths follow the view store's gateway rules: the path's tenant is the token's and its owner the token's
  subject; shared views are written with `wow.analytics.shared-view-role`, system views by a platform-tenant principal
  with `wow.analytics.system-view-role`; `CoSec-App-Id` is set to `wow.analytics.app-id`;
- a dataset query reads the tenant CoSec authenticated, and the ABAC tags of the principal's `abac.*` attributes;
  a principal without tags is refused (`wow.query.abac.require-principal-tags`).

## Console

`console` signs in with the CoSec token the enterprise issued (a single sign-on that stores it under `cosec-token`
signs the console in too), lists the published datasets and the boards, opens a dataset's workbench and the boards'
workbench, and gives the registry's admins its management page: draft from a table, revise the declaration, review
each field's sensitivity, publish and retire. The server answers each console page (`/datasets/…`, `/boards`, …) with
its `index.html`.

```bash
pnpm --filter wow-analytics-console... build
```

`pnpm dev` in `console` forwards the API to `ANALYTICS_SERVER` (`http://localhost:8080`).

## Run it

```bash
./gradlew :wow-analytics-server:installDist
```

Build the console first: the distribution copies `console/dist` into its `ui/`. The distribution is in `analytics/wow-analytics-server/build/install/wow-analytics-server`; review
`config/application.yaml` (MongoDB, Redis, Kafka, ClickHouse) and start `bin/wow-analytics-server` with
`COSEC_JWT_SECRET` set. The end-to-end tests run on Testcontainers:

```bash
./gradlew :wow-analytics-server:integrationTest
```
