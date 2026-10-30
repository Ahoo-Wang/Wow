# AGENTS.md — @ahoo-wang/wow-client

<!-- This file provides coding agents with context about this package. -->

The client of a Wow service's routes and the query DSL. Workspace rules live
in `typescript/AGENTS.md`; the design is
`documentation/designs/` (the routes in `05-read-and-contract.md` and
`08-data-protocols.md`, the decisions in `decisions.md`), which the doc
comments cite by decision number.

## Build & Run Commands

```bash
pnpm --filter @ahoo-wang/wow-client build      # vite build, then scripts/verify-package.mjs
pnpm --filter @ahoo-wang/wow-client test       # vitest with coverage, test:type, then test:api (needs a build)
pnpm --filter @ahoo-wang/wow-client exec vitest run test/client.test.ts
pnpm --filter @ahoo-wang/wow-client exec vitest run test/publicSurface.test.ts -u   # accept a surface change
pnpm --filter @ahoo-wang/wow-client test:api -u   # accept a change to the API reports (after a build)
pnpm --filter @ahoo-wang/wow-client lint
```

## Wow conformance

**Rules.** Every rule Wow enforces on a query by throwing is accounted for in
`test/dsl/wowConformance.test.ts`. Each entry names the rule verbatim, cites
its Kotlin source, and says which of three things this package does with it:
mirrors it (with an input that breaks it), satisfies it by the shape of the
builders, or leaves it to the server (with the reason). Add an entry in the
same change as any new rule.

**Codes and descriptor.** `test/dsl/queryErrorCodes.test.ts` holds
`QueryErrorCodes` to the Kotlin `QueryErrorCodes` of `api/query-api` name by
name; `test/dsl/descriptor.test.ts` holds the capability descriptor's types
to `QueryModelDescriptor.kt` and `FilterOperatorSpec.kt` of `query/query`;
`test/frameworkErrors.test.ts` holds `FrameworkErrorCodes` to the server's.

**Date patterns.** `datePattern` is checked against `java.time`'s pattern
grammar before it is sent. `DatePatternCorpusTest` in wow-api hands about
3,700 patterns to the server's entry and writes which the JVM accepts to
`test/fixtures/java-date-patterns.json`; `test/dsl/datePattern.test.ts` holds
this package to the file entry by entry. To change the corpus, edit the
Kotlin test and regenerate from the repository root with
`./gradlew :wow-api:test --tests "*DatePatternCorpusTest" -Dwow.snapshot.update=true`.

## Project Structure

```
src/
  index.ts        — Root entry `@ahoo-wang/wow-client`: the clients, problems, wire types
  dsl.ts          — `/dsl` entry: the query DSL without HTTP code
  testing.ts      — `/testing` entry: the development caller and the wire builders for a test's fake; loads no test runner
  developmentCaller.ts — DevelopmentCallerHeaders, developmentCaller(Headers): the caller a development run reads (root and /testing)
  testing/wire.ts — commandResultOf, problemOf, problemResponse, snapshotOf
  wire.ts         — The wire model: CommandResult, Hop, Snapshot, EventStream, …
  problem.ts      — Problem, FrameworkErrorCodes, WowProblemError and its retry hint, toWowProblemError, requestIdOf
  wait.ts         — wait.* hops, formatWaitPlan, formatWaitTimeout (the Wow-Wait grammar)
  http.ts         — WowHeaders, WowClientOptions, RequestOptions, formatScope; (internal) send, path expansion
  streams.ts      — CommandResultStream; (internal) the SSE readers of command results and query rows, and their resumption
  commands.ts     — CommandClient (send, wait and stream of a CommandRequest, Kotlin's), CommandRequest, CommandOptions, CommandClientOptions, LifecycleCommands
  queries.ts      — QueryModelClient, SnapshotQueryClient, EventQueryClient, DatasetClient
  state.ts        — StateClient: state reads at a version or a time, and traces
  manifest.ts     — The Manifest's types and fetchManifest (GET /wow/manifest)
  dsl/            — The query DSL; imports no client module and no fetcher package
    field.ts, deletionState.ts, sort.ts, projection.ts, pagination.ts, cursorQuery.ts,
    queryable.ts, documents.ts, descriptor.ts, queryErrorCodes.ts
    filter/       — `filter.*`, FilterExpression and its operators; (internal) validate.ts, datePattern.ts, scope.ts
    aggregation/  — `aggregation.*`, `aggregation.having.*`, `aggregation.derived(…)`; (internal) admit.ts, sort.ts
scripts/
  verify-package.mjs — Run by the build: entries resolve and export what test/surface/ lists, /dsl loads no fetcher package, the root entry tree-shakes, each entry under its gzip ceiling
  size-budget.json   — The gzip regression ceilings of the root entry and /dsl (typescript/AGENTS.md「Size ceilings」)
  api-report.mjs     — `pnpm test:api`: holds the built declarations to test/api/ (-u to accept a change) and fails on a top-level declaration without a doc comment
test/
  surface/, publicSurface.test.ts — The public surface of each entry, one name a line
  api/                — API Extractor reports: the signatures of each entry
  layerBoundaries.test.ts — Each layer rule of eslint.config.js fires on a violation, and src/ has none
  golden/dsl-wire.json, dslWire.test.ts — Every DSL builder's JSON
  client.test.ts, edges.test.ts, resumableStreams.test.ts — The clients against a stubbed fetch (fixtures/fetchStub.ts)
  dsl/                — The DSL, its conformance register and its codes
```

## Layers

`eslint.config.js` enforces the directions (`layerBoundaries`):

- `dsl/` imports no client module (`../http.js`, `../queries.js`, …), no
  `@ahoo-wang/fetcher*` and no `reflect-metadata`.
- The client modules at the top of `src/` import `dsl/` as types only.
- `testing/` imports no test runner (`vitest`, `jest`, `mocha`).
- The entries (`index.ts`, `dsl.ts`, `testing.ts`) only re-export.

A new edge is a design change: change the rule and
`test/layerBoundaries.test.ts` together.

## Errors

The service answers a refusal with an RFC 9457 problem
(`application/problem+json`); every call rejects with a `WowProblemError`
carrying the problem, its status, its Wow code, its violations and, for a
command, its `CommandResult`. A stream that fails after its first event
errors with the same error at the error event, so a `for await` throws
instead of reading the problem as a row. Every command carries a request id,
and whatever it rejects with carries that id (`requestIdOf`).

## Public surface

The root entry (`src/index.ts`), `/dsl` (`src/dsl.ts`) and `/testing`
(`src/testing.ts`) are the only entries. Their exports are listed name by name under `test/surface/`; a new
export, or a removed one, changes a list, and the change is made on purpose
with `pnpm exec vitest run test/publicSurface.test.ts -u`. The build runs
`scripts/verify-package.mjs`, which holds the built entries to the same
lists, checks that `/dsl` loads no fetcher package, and bundles a probe that
imports only `readProblem` and `wait` from the root entry and fails if it
still loads `@ahoo-wang/fetcher` (a control probe importing `CommandClient`
must load it, so the check cannot pass vacuously), and that `/testing` loads no
test runner. The build keeps one output
module per source file (`preserveModules`), so with `sideEffects: false` an
application's bundler drops what it does not import.

`test/api/{root,dsl,testing}.api.md` are API Extractor reports of the built
declarations. `pnpm test:api` (the last step of `pnpm test`, after a build)
fails when the build differs; `pnpm test:api -u` accepts a change. It also
fails when a top-level declaration of an entry has no doc comment.
`test/golden/dsl-wire.json` holds every DSL builder's JSON; accept a change on
the wire with `pnpm exec vitest run test/dslWire.test.ts -u`. Prettier leaves
`test/api/` and `test/golden/` alone.

## Dependencies

- `@ahoo-wang/fetcher` — the HTTP client every request goes through
- `@ahoo-wang/fetcher-eventstream` — the server-sent event streams

## Code Style

- TypeScript strict mode
- Apache 2.0 license headers
- Prettier: single quotes, trailing commas, semicolons, 80 char width

## Boundaries

- ✅ Adding filter operators or expression types in `dsl/filter/` (enum member, type-union member, one delegating method on `filter`)
- ✅ Adding aggregation groups or metrics in `dsl/aggregation/`
- ✅ Writing new tests
- ⚠️ Changing a client's signature — wow-react, wow-view-engine, wow-view-store and wow-generator's output build on it
- ⚠️ Changing the `FilterExpression` API — view-engine and react build on it
- 🚫 Changing what goes on the wire without the server's route changing with it
- 🚫 Changing the Manifest's types without wow-generator, which reads them
