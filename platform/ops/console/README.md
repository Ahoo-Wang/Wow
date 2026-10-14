# Wow Ops Console

The operator's console of the wow-ops service (roadmap 7.1b, decisions D14 and D141): every service's parked entries in one place, and an operator's actions on them. Ops never retries or skips anything itself: each action is recorded on the entry and sent by the ops saga to the owning service as the ledger's system command (`wow.ledger.*`), which runs it; what came of it arrives as the service's next record.

It replaces the 9.x compensation dashboard (`compensation/dashboard`), which stays until M9 (D146).

## What it does

- **Parked entries** in the view engine's workbench (`@ahoo-wang/wow-view-engine`), over `POST /ops/parked/snapshot/query`. The system views are an operator's queues: _Needs attention_ (exhausted or unrecoverable), _Retrying_, _Unrecoverable_, _Resolved_, _All_, and _Parked by context_ (an analysis). Every field filters, sorts and groups as the service's descriptor allows: context, service, subscription, aggregate, status, recoverability, the operator's request, the outcome, attempts and the times (`state.failedAt` is the time filter). Saved views are kept in this browser: the ops service hosts no view store.
- **Detail** of an entry (the address keeps it, `?id=`): its cause, the owning aggregate and where it parked, its attempts against its retry spec, the operator's last request and how it was resolved.
- **Actions**: resume (with a reason), skip (with a reason), mark recoverable, apply a retry spec. Each asks first, runs over one entry or a selection, waits for the entry's snapshot (`Wow-Wait: snapshot`), and reports its outcome. A refusal is said in the service's words and code (`application/problem+json`: `ops.parked.resolved`, `ops.parked.invalid`, `wow.auth.forbidden`, ...). A resolved entry takes no action.
- **Updates**: every record view reads the entries again every 15 seconds, and right after an action.
- **Caller**: the ops service reads the caller from headers (`X-User`, `X-Tenant`, `X-Role`), standing in for CoSec; the console asks for one and keeps it in this browser. Acting needs the role `ops.operator`. A deployment behind CoSec replaces `CallerHeaders` (`src/services/caller.ts`) with CoSec's token interceptor.
- English and Chinese.

The client is generated from the ops service's Manifest (`src/generated/`, by wow-generator, on `@ahoo-wang/wow-client/v10`); `src/generated.test.ts` regenerates it and compares byte for byte.

## How it is served

The ops server serves the console's build as static files (`spring.web.resources.static-locations`, `platform/ops/ops-server/src/main/resources/application.yaml`): the distribution (`./gradlew :ops-server:installDist`) copies `dist/` into its `ui/` folder, and `./gradlew :ops-server:run` serves `../console/dist/` from the project directory. Build the console first. The console uses a hash router (`/#/parked`), so the server needs no fallback for its pages.

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

`pnpm test:e2e` runs Playwright against the real ops server in a JVM of its own, on its memory backend with its snapshot queries on (`wow.query.memory.enabled`, D209), serving `dist/` (`e2e/start-ops-server.mjs`; Playwright starts and stops it). The owning services' ledgers are the server's ledger simulator (`LedgerSimulatorRoutes`, in the ops server's test classes). Build first, from the repository root, with JDK 21:

```bash
./gradlew -Pwow.scope=v10 :ops-server:installDist :ops-server:testClasses
pnpm --dir platform/ops/console build
pnpm --dir platform/ops/console test:e2e   # OPS_E2E_CHANNEL=chrome uses the installed Chrome
```

`WOW_OPS_URL` runs the suite against a server already running with the same configuration.
