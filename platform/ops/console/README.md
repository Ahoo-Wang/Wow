# Wow Ops Console

The operator's console of the wow-ops service: every service's parked entries in one place, and an operator's actions on them. Ops never retries or skips anything itself: each action is recorded on the entry and sent by the ops saga to the owning service as the ledger's system command (`wow.ledger.*`), which runs it; what came of it arrives as the service's next record.

It replaces the 9.x compensation dashboard, which left with the 9.x code and stays on `v9`.

## What it does

- **Overview** (the home page, `/#/overview`): the overview's system board (`src/views/overview.ts`), embedded in the view engine's interactive tier. Its one filter is a window over when the entries failed (the last 7 days until another is picked). The numbers: parked in the window and in all, needing attention, retrying, unrecoverable, resolved; each opens the queue it counts. Parked over time (by day, a stacked bar per status), by status, the top 10 failure clusters, by context, the top failing subscriptions, and the entries needing attention. A cluster opens its parked entries in the workbench. It reads again every 15 seconds.
- **Parked entries** in the view engine's workbench (`@ahoo-wang/wow-view-engine`), over `POST /ops/parked/snapshot/query`. The system views are an operator's queues: _Needs attention_ (exhausted or unrecoverable), _Retrying_, _Unrecoverable_, _Resolved_, _Parked_ (not resolved), _All_; and analyses: _Parked by context_, _Failure clusters_ (by error type, context and subscription, with the first and last failure; a row drills into its entries), _Top failing subscriptions_ and _Parked over time_. Every field filters, sorts and groups as the service's descriptor allows: context, service, subscription, aggregate, status, error type, recoverability, the operator's request, the outcome, attempts and the times (`state.failedAt` is the time filter; the times are epoch milliseconds the query engine knows as such, `@QueryTemporal`, so they bucket by day). Saved views are kept in this browser: the ops service hosts no view store.
- **Detail** of an entry (the address keeps it, `?id=`): its cause (the error type and the message, as the owning service recorded them, with a copy button), the owning aggregate and where it parked, its attempts against its retry spec, the operator's last request and how it was resolved. Then its **history**, newest first: the entry's own event streams (`POST /ops/parked/events/query`) read as a timeline — parked, parked again, each retry the service recorded, each operator's request and who asked, and the resolution. It is read again whenever the entry is.
- **Actions**: resume (with a reason), skip (with a reason), mark recoverable, apply a retry spec. Each asks first, runs over one entry or a selection, waits for the entry's snapshot (`Wow-Wait: snapshot`), and reports its outcome. A refusal is said in the service's words and code (`application/problem+json`: `ops.parked.resolved`, `ops.parked.invalid`, `wow.auth.forbidden`, ...). A resolved entry takes no action.
- **Updates**: every record view and the overview read the entries again every 15 seconds, and right after an action. The ops service streams no changes of its snapshots (a query's `list` can answer as server-sent events, but it ends with the list), so the console polls.
- **Sign-in**: a deployment of the ops service (its `cosec` profile, behind the CoSec gateway that verifies it) takes a CoSec access token: the operator signs in by pasting one (`/#/caller`), which the console keeps where CoSec front ends keep theirs (`cosec-token`, `@ahoo-wang/fetcher-cosec`), so a single sign-on that stored one signs the console in too. Every request carries it (`Authorization: Bearer`) with the console's application, `CoSec-App-Id: ops-console`. The console reads the operator from the token (its subject, tenant and roles); the service needs the role `ops.viewer` or `ops.operator` to read the entries, `ops.operator` to act. The console cannot refresh the token: a token the service refuses (401) signs it out. For a local run, the ops service reads the caller from headers instead (`Wow-User`, `Wow-Tenant`, `Wow-Roles`), only in a development profile (`./gradlew :ops-server:run` and the end-to-end suite run it in `dev`; a request without `Wow-User` is 401); the same page names one and keeps it in this browser (`src/services/caller.ts`).
- English and Chinese.

What it does not show of the 9.x dashboard: a failure's stack trace. The owning services record a failure as `<exception class>: <message>` (the published entry's `reason`) and send no stack trace, so the cause is that line; the error type is its class (`state.errorType`).

The client is generated from the ops service's Manifest (`src/generated/`, by wow-generator, on `@ahoo-wang/wow-client`); `src/generated.test.ts` regenerates it and compares byte for byte.

## How it is served

The ops server serves the console's build as static files (`spring.web.resources.static-locations`, `platform/ops/ops-server/src/main/resources/application.yaml`): the distribution (`./gradlew :ops-server:installDist`) copies `dist/` into its `ui/` folder, and `./gradlew :ops-server:run` serves `../console/dist/` from the project directory. Build the console first. The console uses a hash router (`/#/overview`, `/#/parked`), so the server needs no fallback for its pages.

## Develop

From the repository root, once:

```bash
pnpm install
pnpm --filter wow-ops-console^... build
```

Then, in `platform/ops/console`:

```bash
pnpm dev          # Vite on :5173, forwarding /ops and /wow to OPS_SERVER (http://localhost:8088)
pnpm lint
pnpm test         # Vitest: unit and component tests
pnpm build        # tsc and vite build into dist/
pnpm generate     # regenerate src/generated/ from the ops server's Manifest baseline
```

## End to end

`pnpm test:e2e` runs Playwright against the real ops server in a JVM of its own, on its memory backend, which serves its snapshot queries, serving `dist/` (`e2e/start-ops-server.mjs`; Playwright starts and stops it). The owning services' ledgers are the server's ledger simulator (`LedgerSimulatorRoutes`, in the ops server's test classes). Build first, from the repository root, with JDK 21:

```bash
./gradlew :ops-server:installDist :ops-server:testClasses
pnpm --dir platform/ops/console build
pnpm --dir platform/ops/console test:e2e   # OPS_E2E_CHANNEL=chrome uses the installed Chrome
```

A second server runs in its `cosec` profile, as a deployment does, for `e2e/cosec.spec.ts`: the operator signs in with a CoSec access token the suite issues as the CoSec gateway would (the server holds no secret; port `OPS_E2E_COSEC_PORT`, 18189), a viewer's actions are refused, an expired token signs the console out, and no identity header is trusted.

`WOW_OPS_URL` runs the suite (without the CoSec part) against a server already running with the same configuration.
