# Wow Analytics Catalog Console

The data owners' console of the analytics catalog service (roadmap 7.2c, decisions D238, D241, D251, D259): the warehouse's sources and the wide tables built on them, reviewed, approved and published in one place, and the rows of every dataset served, with actions on the aggregates they refer to.

- **Sources** (the home page, `/#/sources`) in the view engine's workbench (`@ahoo-wang/wow-view-engine`), over `POST /analytics-catalog/source/snapshot/query`. The system views: _All_, _To review_ (drafts), _Awaiting approval_, _Wow tables_, _Analysts' tables_, _Tables gone_ and _By status_. A source's detail (`?id=`) reads its connection, table, status and scope, a Wow table's `wow_catalog` row, the lower protection pending an approver and the rows refused; for an analyst's table it edits the scope (tenant, owner and space columns, or a platform dimension) and the references (`column=context.aggregate`), and every source's columns' protection (a Wow table's may only be tightened). Its actions: mark reviewed, approve for datasets, approve a lower protection.
- **Datasets** (`/#/datasets`), over `POST /analytics-catalog/dataset/snapshot/query`: _All_, _Needs revision_, _Published_, _Drafts_, _Retired_ and _By status_. A dataset's detail reads why it needs revision or an approver, edits its definition (JSON) and title, and lists the versions published, the one serving first, each with its view, sources, fields (a reference field names its aggregate) and DDL. Its actions: publish, approve or refuse a publication, roll back to a version, retire.
- **Rows** (`/#/rows?dataset=`): a served dataset's rows, as any reader of it reads them, through the node's query policies (`/wow/dataset/{context}/{dataset}/query`); the directory (`GET /wow/dataset`) registers each dataset in the engine (`DatasetResources`). A row's REFERENCE field offers the commands of the aggregate it holds the id of (`GET /analytics-catalog/datasets/{dataset}/reference-actions`), each with a form of its body; the console sends one to the catalog's relay (`POST /analytics-catalog/datasets/{dataset}/rows/{field}/{id}/{command}`), which sends it to the owning service as the caller, so that service authorizes it. The browser talks to the catalog alone.
- **Draft** (`/#/draft`): an analyst's table drafted as a source (by its connection, database and table), and a dataset drafted from its definition.
- **Actions** ask first, run over one record or a selection, wait for the record's snapshot (`Wow-Wait: snapshot`), and report their outcome. A refusal is said in the service's words and code (`application/problem+json`: `analytics.catalog.incomplete`, `analytics.catalog.definition-invalid`, `wow.auth.forbidden`, ...).
- **Sign-in**: a deployment of the catalog (its `cosec` profile, behind the CoSec gateway that verifies it) takes a CoSec access token: the data owner signs in by pasting one (`/#/caller`), which the console keeps where CoSec front ends keep theirs (`cosec-token`, `@ahoo-wang/fetcher-cosec`). Every request carries it (`Authorization: Bearer`) with the console's application, `CoSec-App-Id: analytics-catalog-console`. Reading the catalog needs one of the `analytics.catalog.*` roles; an analyst's work needs `analytics.catalog.editor`, a review `analytics.catalog.approver`. A token the service refuses (401) signs the console out. For a local run, the catalog reads the caller from headers instead (`X-User`, `X-Tenant`, `X-Role`), only with `wow.platform.trust-identity-headers=true` (`./gradlew :analytics-catalog-server:run` and the end-to-end suite set it); the same page names one and keeps it in this browser (`src/services/caller.ts`).

The words are in English and Chinese, the browser's language by default (the menu at the top right changes it).

The client is generated from the catalog's Manifest (`src/generated/`, by wow-generator, on `@ahoo-wang/wow-client`); `src/generated.test.ts` regenerates it and compares byte for byte.

The package's version is the framework's (`gradle.properties`), set by hand: it is private and published nowhere.

## Serving

The catalog server serves the console's build as static files (`spring.web.resources.static-locations`, `platform/analytics/catalog/server/src/main/resources/application.yaml`): the distribution (`./gradlew :analytics-catalog-server:installDist`) copies `dist/` into its `ui/` folder, and `./gradlew :analytics-catalog-server:run` serves `../console/dist/` from the project directory. Build the console first. The console uses a hash router (`/#/sources`, `/#/datasets`), so the server needs no fallback for its pages.

A dataset's rows act on aggregates of other services only where the catalog has a client of the owning context described by its Manifest: `wow.clients.contexts.<context>.url` (or `internal-url`) with `contract: manifest`, and `mode: user` to send the commands with the data owner's own credential. A context without one offers no actions.

## Development

From the repository root:

```bash
pnpm install
pnpm --filter wow-analytics-catalog-console^... build
```

Then, in `platform/analytics/catalog/console`:

```bash
pnpm dev          # Vite on :5173, forwarding /analytics-catalog and /wow to CATALOG_SERVER (http://localhost:8087)
pnpm build        # type-check, then build into dist/
pnpm lint
pnpm test         # Vitest and Testing Library
pnpm generate     # regenerate src/generated/ from the catalog's Manifest baseline
```

## End to end

`pnpm test:e2e` runs Playwright against the real catalog server in a JVM of its own, on its memory backend with its snapshot queries on (`wow.query.memory.enabled`, D209), serving `dist/` (`e2e/start-catalog-server.mjs`; Playwright starts and stops it). Its warehouse is the server's warehouse simulator (`WarehouseSimulatorRoutes`, in the server's test classes), which the suite puts its tables in. Build first, from the repository root, with JDK 21:

```bash
./gradlew :analytics-catalog-server:installDist :analytics-catalog-server:testClasses
pnpm --dir platform/analytics/catalog/console build
pnpm --dir platform/analytics/catalog/console test:e2e   # CATALOG_E2E_CHANNEL=chrome uses the installed Chrome
```

A second server runs in its `cosec` profile, as a deployment does, for `e2e/cosec.spec.ts`: the data owner signs in with a CoSec access token the suite issues as the CoSec gateway would (the server holds no secret, decision D296; port `CATALOG_E2E_COSEC_PORT`, 18186), an expired token signs the console out, and no identity header is trusted.
