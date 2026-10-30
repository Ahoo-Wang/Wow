# Wow Analytics Catalog Console

The data owners' console of the analytics catalog service: the warehouse's sources and the wide tables built on them, reviewed, approved and published in one place, and the rows of every dataset served, with actions on the aggregates they refer to.

- **Sources** (the home page, `/#/sources`) in the view engine's workbench (`@ahoo-wang/wow-view-engine`), over `POST /analytics-catalog/source/snapshot/query`. The system views: _All_, _To review_ (drafts), _Awaiting approval_ (reviewed and not approved yet; only where the catalog takes an approver for a source, `require-source-approval`), _Wow tables_, _Analysts' tables_, _Tables gone_ and _By status_. A source's detail (`?id=`) reads its connection, table, status and scope, a Wow table's `wow_catalog` row, the lower protection pending an approver and the rows refused; for an analyst's table it edits the scope (tenant, owner and space columns, or a platform dimension) and the references (`column=context.aggregate`), and every source's columns' protection (a Wow table's may only be tightened). Its actions: mark reviewed, approve for datasets, approve a lower protection.
- **Datasets** (`/#/datasets`), over `POST /analytics-catalog/dataset/snapshot/query`: _All_, _Needs revision_ (with whether each still serves its published version), _Awaiting approval_ (the publications an approver decides), _Published_, _Drafts_, _Retired_ and _By status_. A dataset's detail reads why it needs revision or an approver, edits its definition (JSON, a mistake said by its line and column) and title, and lists the versions published, the one serving first, then the newest, each with its view, sources, fields (a reference field names its aggregate) and DDL, and each other one with the way back to it. Its actions: publish (one that takes an approver awaits one: the outcome says it may, and every one does where `require-publication-approval` is on), approve or refuse a publication awaiting one, roll back to a version (not the one serving, nor one never published), retire.
- **Rows** (`/#/rows?dataset=`): a served dataset's rows, as any reader of it reads them, through the node's query policies (`/wow/dataset/{context}/{dataset}/query`); the directory (`GET /wow/dataset`) registers each dataset in the engine (`DatasetResources`). A row's REFERENCE field offers the commands of the aggregate it holds the id of (`GET /analytics-catalog/datasets/{dataset}/reference-actions`), each with a form of its body; the console sends one to the catalog's relay (`POST /analytics-catalog/datasets/{dataset}/rows/{field}/{id}/{command}`), which sends it to the owning service as the caller, so that service authorizes it. The browser talks to the catalog alone. A node without a dataset query backend (a local run on the memory backend) serves none, and the page says so.
- **Dashboards** (`/#/boards`): boards whose panels are views of any dataset served, of whichever service, side by side, built by the data owners and kept in this browser.
- **Draft** (`/#/draft`): an analyst's table drafted as a source (by its connection, the catalog's own until another is typed, its database and table), and a dataset drafted from its definition; each drafted links to its record, open on its page.
- **Settings**: what the console offers follows the catalog's settings (`GET /analytics-catalog/settings`, read once a caller is in force): its connection, the approvals it takes, and whether its roles decide who sends what. Where they do (no CoSec policies deciding), an action the caller's roles rule out is offered disabled, with the role it takes; so is one the record rules out, with why.
- **Actions** ask first, run over one record or a selection, wait for the record's snapshot (`Wow-Wait: snapshot`), and report their outcome. A refusal is said in the service's words and code (`application/problem+json`: `analytics.catalog.incomplete`, `analytics.catalog.definition-invalid`, `wow.auth.forbidden`, ...).
- **Updates**: the lists of sources and datasets are read again every 15 seconds, and right after an action; the catalog streams no changes of its snapshots.
- **Sign-in**: a deployment of the catalog (its `cosec` profile, behind the CoSec gateway that verifies it) takes a CoSec access token: the data owner signs in by pasting one (`/#/caller`), which the console keeps where CoSec front ends keep theirs (`cosec-token`, `@ahoo-wang/fetcher-cosec`). Every request carries it (`Authorization: Bearer`) with the console's application, `CoSec-App-Id: analytics-catalog-console`. Reading the catalog needs one of the `analytics.catalog.*` roles; an analyst's work needs `analytics.catalog.editor`, a review `analytics.catalog.approver`. The gateway verifies the token and forwards the caller to the service in `CoSec-Identity`; the service reads only that. A token refused (401, by the gateway or the service) signs the console out, and the sign-in page says why (expired, or refused). For a local run, the catalog reads the caller from headers instead (`Wow-User`, `Wow-Tenant`, `Wow-Roles`), only in a development profile (`./gradlew :analytics-catalog-server:run` and the end-to-end suite run it in `dev`); the same page names one and keeps it in this browser (`src/services/caller.ts`). A deployment refuses such a caller, which signs it out and says so.

The words are in English and Chinese: the first of the browser's languages it speaks, until the menu at the top right picks one.

The client is generated from the catalog's Manifest (`src/generated/`, by wow-generator, on `@ahoo-wang/wow-client`); `src/generated.test.ts` regenerates it and compares byte for byte.

The package's version is the framework's (`gradle.properties`), set by hand: it is private and published nowhere.

## Serving

The catalog server serves the console's build as static files (`spring.web.resources.static-locations`, `platform/analytics/catalog/server/src/main/resources/application.yaml`): the distribution (`./gradlew :analytics-catalog-server:installDist`) copies `dist/` into its `ui/` folder, and `./gradlew :analytics-catalog-server:run` serves `../console/dist/` from the project directory. Build the console first. The console uses a hash router (`/#/sources`, `/#/datasets`), so the server needs no fallback for its pages.

A dataset's rows act on aggregates of other services only where the catalog has a client of the owning context described by its Manifest: `wow.peers.<context>.public-url` (or `url`) with `contract: manifest`, and `mode: user` to send the commands with the data owner's own credential. A context without one offers no actions.

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

`pnpm test:e2e` runs Playwright against the real catalog server in a JVM of its own, on its memory backend, which serves its snapshot queries, serving `dist/` (`e2e/start-catalog-server.mjs`; Playwright starts and stops it). Its warehouse is the server's warehouse simulator (`WarehouseSimulatorRoutes`, in the server's test classes), which the suite puts its tables in. Build first, from the repository root, with JDK 21:

```bash
./gradlew :analytics-catalog-server:installDist :analytics-catalog-server:testClasses
pnpm --dir platform/analytics/catalog/console build
pnpm --dir platform/analytics/catalog/console test:e2e   # CATALOG_E2E_CHANNEL=chrome uses the installed Chrome
```

A second server runs in its `cosec` profile, as a deployment does, for `e2e/cosec.spec.ts`, behind a stand-in of the CoSec gateway (`e2e/cosec-gateway.mjs`, port `CATALOG_E2E_COSEC_PORT`, 18186; the server on `CATALOG_E2E_SERVICE_PORT`, 18185): the suite issues CoSec access tokens, the stand-in verifies them and forwards the caller in `CoSec-Identity` as CoSec's gateway encodes it, a request without a token goes on with no identity, and a client's own `CoSec-Identity` is stripped (the server holds no secret). The data owner signs in with a token, an expired token signs the console out, and no identity header a client sends is trusted.
