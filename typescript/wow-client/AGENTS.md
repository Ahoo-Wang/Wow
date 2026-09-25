# AGENTS.md — @ahoo-wang/wow-client

<!-- This file provides coding agents with context about this package. -->

## Build & Run Commands

```bash
# Build this package
pnpm --filter @ahoo-wang/wow-client build

# Run tests
pnpm --filter @ahoo-wang/wow-client test

# Run a single test file
pnpm --filter @ahoo-wang/wow-client exec vitest run test/command/commandHttpHeaders.test.ts

# Lint
pnpm --filter @ahoo-wang/wow-client lint

# Clean
pnpm --filter @ahoo-wang/wow-client clean
```

## Wow conformance

This package mirrors Wow's query protocol, and three things hold it there.

**Rules.** Every rule Wow enforces by throwing is accounted for in
`test/query/wowConformance.test.ts`. Each entry names the rule verbatim, cites
its Kotlin source, and says which of three things this package does with it:
mirrors it (with an input that breaks it), satisfies it by the shape of the
builders, or leaves it to the server (with the reason). Add an entry in the
same change as any new rule. The server enforces every rule regardless; the
register is what makes this package's answer to each one explicit and tested.

**Wire values.** `typescript/integration-test/test/wow/wowOpenApi.test.ts` holds every
enum this package sends — filter operators, aggregation and expression types,
sort directions — to the OpenAPI document of the Wow example server CI runs.
The server writes that document from its own types, so it states exactly
what Wow accepts. Renovate bumps the server's version, so a Wow release that
changes the protocol fails that pull request rather than someone's
application. A new enum belongs in the test's list. `Operator`, the
deprecated Condition API's, is left out: the document describes only
`FilterExpression`.

**Date patterns.** `datePattern` is checked against `java.time`'s pattern
grammar before it is sent. `DatePatternCorpusTest` in wow-api hands about
3,700 patterns to the server's entry (`TodayFilter(datePattern = …)`) and
writes which the JVM accepts to `test/fixtures/java-date-patterns.json`; it
fails when that file no longer matches the JVM.
`test/dsl/datePattern.test.ts` holds this package to the file entry by entry.
To change the corpus, edit the Kotlin test and regenerate from the repository
root with
`./gradlew :wow-api:test --tests "*DatePatternCorpusTest" -Dwow.snapshot.update=true`.

## Testing

- Vitest with `globals: true` and `@vitest/coverage-v8`
- Test files: `*.test.ts` in a `test/` directory at the package root (mirroring `src/`)
- Run with `--coverage` flag by default

## Project Structure

The design, `docs/design/architecture.md`, explains the layers, the error and
stream model, and the baselines, and keeps the 2026-09 refactor plan with
each batch's decisions as its appendix; this is the layout.

Files a folder's `index.ts` does not re-export are internal; they are marked
"(internal)" below.

```
src/
  index.ts                    — Root entry `@ahoo-wang/wow-client`
  dsl.ts                      — `/dsl` entry: the query DSL without HTTP code
  dsl/                        — The query DSL; imports no HTTP code
    field.ts                  — (internal) QueryField path check for filter, sort and projection
    deletionState.ts          — DeletionState, which filter.deletion() takes
    sort.ts                   — Sort specifications
    projection.ts             — Field projection
    pagination.ts             — Pagination support
    cursorQuery.ts            — Forward-only cursor query and CursorPage
    queryable.ts              — Query request shapes (Filter*Query, PagedList) and their factories
    documents.ts              — DynamicDocument type aliases
    filter/
      operator.ts             — FilterOperator, StringComparison, SearchMode, TimeUnit
      types.ts                — The filter shapes, FilterExpression, ElementFilterExpression
      builders.ts             — `filter.*`: one builder per shape keyed by operator, one delegating method (with its JSDoc) per operator
      validate.ts             — (internal) literal, non-empty, zone, days and local-time checks
      datePattern.ts          — (internal) java.time pattern syntax, held to test/fixtures/java-date-patterns.json
      scope.ts                — (internal) the root-filter check element predicates share
      index.ts
    aggregation/
      types.ts                — Groups, metrics, expressions, the derived and having shapes, the option types, AGGREGATION_LIMITS
      admit.ts                — (internal) the cross-part rules aggregation.query() enforces
      sort.ts                 — (internal) effectiveSort: the order Wow applies to grouped rows
      builders.ts             — `aggregation.*`
      having.ts               — HavingDsl: `aggregation.having.*`
      derived.ts              — DerivedExpressionDsl: what `aggregation.derived(d => …)` hands its callback
      index.ts
  transport/                  — What the clients share on the wire; the only importer of @ahoo-wang/fetcher-eventstream
    eventStreams.ts           — Stream result extractors that end a stream with a WowError at a server error event
    endpoints.ts              — The endpoint presets COMMAND_STREAM_ENDPOINT / QUERY_STREAM_ENDPOINT (Accept header + extractor)
    index.ts
  client/
    routing.ts                — ResourceAttributionPathSpec, UrlPathParams
    command/
      commandClient.ts        — Command client for sending CQRS commands
      commandHeaders.ts       — CommandHeaders: the command header names, as literal types
      commandRequest.ts       — CommandRequest, typed CommandRequestHeaders, commandHeaders() and waitStrategy()
      commandResult.ts        — CommandResultEventStream (the result and wait signal types are in model/)
      types.ts                — Command bodies (CommandBody, DeleteAggregate, …), CompensationTarget, BatchResult
      index.ts
    metadata/
      wowMetadata.ts          — Wow metadata types (WowMetadata, BoundedContext, Aggregate)
      wowMetadataClient.ts    — WowMetadataClient: GET /wow/metadata
      index.ts
    query/
      queryApi.ts             — Generic QueryApi (list, paged, cursor, aggregate, count)
      requests.ts             — (internal) The *QueryRequest unions the query clients take; the only file outside legacy/ that imports from it
      factory.ts              — QueryClientFactory; hands each client only ApiMetadata keys
      index.ts
      event/
        domainEventStream.ts          — Domain event stream types
        eventStreamQueryApi.ts        — Event stream query API (no single; + load, loadStream)
        eventStreamQueryClient.ts     — Event stream query client, plus load(id, head, tail)
        endpointPaths.ts              — (internal) its endpoint paths
        index.ts
      snapshot/
        snapshot.ts                   — Materialized snapshot types
        snapshotQueryApi.ts           — Snapshot query API (+ *State variants, getById/getByIds and their *State)
        snapshotQueryClient.ts        — Snapshot query client
        endpointPaths.ts              — (internal) its endpoint paths
        index.ts
      state/
        loadStateAggregateApi.ts            — LoadStateAggregateApi, the interface of the client below
        loadStateAggregateClient.ts         — Load state aggregate client
        loadOwnerStateAggregateApi.ts       — LoadOwnerStateAggregateApi, the interface of the client below
        loadOwnerStateAggregateClient.ts    — Load by owner state client
        endpointPaths.ts                    — (internal) their endpoint paths
        index.ts
  model/                      — The wire model: wow-api's mixins and the command results; types and wire enums only
    command.ts                — CommandStage, CommandResult, WaitSignal and their mixins (CommandId, RequestId, …)
    abac.ts, bi.ts, common.ts, function.ts,
    messaging.ts, modeling.ts, naming.ts, index.ts
  error/                      — The one error shape; imports no fetcher package
    errorInfo.ts              — ErrorInfo, ErrorCodes, ErrorCode, RecoverableType
    wowError.ts               — WowError, isErrorInfo(), toWowError()
    headers.ts                — WowHeaders: Wow-Space-Id, Wow-Error-Code
    index.ts
  legacy/                     — DEPRECATED `@ahoo-wang/wow-client/legacy` entry, removed in v10
    index.ts                  — The entry
    condition.ts              — Condition model and builders
    operator.ts               — Operator enum — use FilterOperator
    queryable.ts              — Condition queries and their factories
    locale/                   — i18n for the Operator enum (en_US, zh_CN)
scripts/
  verify-package.mjs          — Run by the build: entries resolve, export what test/surface/ lists, /dsl loads no HTTP code, the root entry tree-shakes, no declaration maps
  api-report.mjs              — `pnpm test:api`: holds the built declarations to test/api/ (-u to accept a change)
test/
  surface/                    — The public surface of each entry, one name a line
  publicSurface.test.ts       — Holds the source entries to those lists (-u to accept a change)
  layerBoundaries.test.ts     — Each layer rule of eslint.config.js fires on a violation, and src/ has none
  api/                        — API Extractor reports: the signatures of each entry
  golden/                     — Wire baselines: dsl-wire.json, client-endpoints.json
  fixtures/java-date-patterns.json — What DateTimeFormatter.ofPattern accepts, written by wow-api's DatePatternCorpusTest
  dsl/datePattern.test.ts     — datePattern against that corpus, entry by entry
  dslWire.test.ts             — Every DSL builder's JSON against golden/dsl-wire.json
  clients/                    — Every client method against a stubbed fetch
    endpointTable.test.ts     — Every client method's requests against golden/client-endpoints.json
```

## Layers

`eslint.config.js` enforces which folder of `src/` may import which
(`@typescript-eslint/no-restricted-imports`, one block per layer;
`docs/design/architecture.md` §2.2 draws the graph):

- `dsl/`, `model/` and `error/` import no `client/`, `transport/`, `legacy/`
  or `@ahoo-wang/fetcher*`. `dsl/` may import `model/`; `model/` may import
  `error/`'s types only (a command result is an `ErrorInfo`); `error/` imports
  nothing outside itself.
- `transport/` imports `error/`, `model/`, fetcher and fetcher-eventstream,
  never `client/`, `dsl/` or fetcher-decorator.
- `client/` imports everything below it except fetcher-eventstream (its
  streams answer rows, so it names no event type), and `legacy/` only from
  `client/query/requests.ts`, as types.
- `legacy/` imports only `dsl/`. The entries (`index.ts`, `dsl.ts`,
  `legacy/index.ts`) only re-export and are not restricted.

A new edge is a design change: change §2.2 of the design page and the rule together, and add the
edge to `test/layerBoundaries.test.ts`.

## Errors

A server error reaches an application as a `WowError` (`errorCode`,
`errorMsg`, `bindingErrors`, `status`):

- A non-2xx response rejects with the fetcher's `ExchangeError`; the
  application calls `toWowError(error)`, which reads the `ErrorInfo` body (or
  the `Wow-Error-Code` header) from a clone of the response. It returns
  `undefined` for failures the Wow server did not answer. There is no
  interceptor that rewrites the fetcher's error: the clients share the
  application's Fetcher, and the fetcher wraps whatever an error interceptor
  leaves in an `ExchangeError` anyway.
- A server-sent event stream answers HTTP 200 and, on failure, sends one last
  event named by the error code. The stream extractors in
  `src/transport/eventStreams.ts` unwrap each event to its data, so a stream
  yields the rows (or command results) themselves, and error the stream with a
  `WowError` at the error event, so a `for await` throws instead of reading the
  `ErrorInfo` as a row. Every
  built-in stream method takes them through the endpoint presets in
  `src/transport/endpoints.ts`, `COMMAND_STREAM_ENDPOINT` and
  `QUERY_STREAM_ENDPOINT` (the `Accept` header and the extractor together);
  a new stream method, and generated code, uses a preset rather than spelling
  the two out.

## Public surface

The root entry (`src/index.ts`), `/dsl` (`src/dsl.ts`) and `/legacy`
(`src/legacy/index.ts`) are the only entries. Their exports are listed name by name under `test/surface/`; a
new export, or a removed one, changes a list, and the change is made on
purpose with `pnpm exec vitest run test/publicSurface.test.ts -u`. The build
runs `scripts/verify-package.mjs`, which holds the built ES module and
CommonJS entries to the same lists. Nothing Condition-based is exported from
the root entry, and `/dsl` must import nothing that reaches a fetcher package or
`reflect-metadata` (the build checks this). An internal helper goes in a file
its folder's `index.ts` does not list.

The build keeps one output module per source file (`preserveModules` in
`vite.config.ts`), so with `sideEffects: false` an application's bundler drops
what it does not import. `verify-package.mjs` bundles a probe that imports only
`toWowError`, `waitStrategy` and `WowHeaders` from the built root entry and
fails if it still loads a fetcher package; a second probe importing
`CommandClient` must load fetcher-decorator, so the check cannot pass vacuously.
A module with a top-level side effect that a client does not need breaks this.

The names are not the whole contract; three baselines hold the rest, and a
refactor must leave all three unchanged:

- **Signatures.** `test/api/{root,dsl,legacy}.api.md` are API Extractor
  reports of the built declarations: every export with its parameter and
  return types, generic defaults, optional markers and enum values, plus the
  shapes of the unexported types a signature names. `pnpm test:api` (the last
  step of `pnpm test`, after a build) fails when the build differs and writes
  the new report to a temporary folder; `pnpm test:api -u` accepts a change.
- **DSL wire protocol.** `test/dslWire.test.ts` runs every builder `/dsl`
  exports on fixed input and compares the JSON with
  `test/golden/dsl-wire.json`. A builder without a case fails the test.
- **Client endpoints.** `test/clients/endpointTable.test.ts` calls every
  public method of every client (found by reflection on its prototype) and
  compares the requests (method, URL, all headers, body), the result, and
  where each stream stops at an error event with
  `test/golden/client-endpoints.json`.

The golden files sort object keys, so only a change on the wire fails them;
accept one with `pnpm exec vitest run <test> -u`. Prettier leaves
`test/api/` and `test/golden/` alone.

### Key Concepts

- **Command Client**: Sends CQRS commands with wait strategies (sent, processed, snapshot)
- **Query Clients**: Type-safe query builders for snapshots, event streams, and state aggregates
- **Filter Expressions**: `filter.*` builders produce the `FilterExpression` tree the filterable `QueryApi` operations take — the current API. It is optional on `AggregationQuery`, and the load-state clients accept no filter at all
- **Aggregation**: `aggregation.*` builds groups, metrics and arithmetic expressions, and `aggregation.query()` admits the assembled query against the rules Wow enforces in its constructor. `aggregation.having.*` (`HavingDsl`, after Kotlin's `HavingDsl`) builds the `having`, and `aggregation.derived(d => …, alias)` builds a derived metric's arithmetic with the `DerivedExpressionDsl` it hands the callback (after Kotlin's `DerivedExpressionDsl`); `derived()` also still takes a built tree. Each builder refuses its own numbers with Wow's message; references, grouping and depth are `aggregation.query()`'s
- **Legacy Condition API**: `src/legacy/` is the `@ahoo-wang/wow-client/legacy` entry, for Wow servers before 8.11 — superseded by `dsl/filter/`. The root entry never exports it; the query clients accept its request shapes through the `*QueryRequest` unions (see `docs/compat-debt.md`)
- **Wow Metadata**: `WowMetadata` types describing bounded contexts and aggregates (types only, no decorator)

## Dependencies

- `@ahoo-wang/fetcher` — core HTTP client
- `@ahoo-wang/fetcher-eventstream` — SSE streaming for event streams
- `@ahoo-wang/fetcher-decorator` — decorator-based API definitions

## Code Style

- TypeScript strict mode
- Apache 2.0 license headers
- Prettier: single quotes, trailing commas, semicolons, 80 char width

## Git Workflow

- Conventional commits: `feat(wow):`, `fix(wow):`, `test(wow):`
- Version follows `version` in the repository root `gradle.properties`

## Boundaries

- ✅ Adding filter operators or expression types in `dsl/filter/` (enum member, type-union member, one delegating method on `filter`)
- ✅ Adding aggregation groups or metrics in `dsl/aggregation/` (enum member and type in `types.ts`, builder in `builders.ts`, a cross-part rule in `admit.ts`)
- ✅ Writing new tests
- ⚠️ Changing command client API — affects `wow-react` hooks and `wow-generator` output
- ⚠️ Changing the `FilterExpression` API — view-engine and react build on it
- ⚠️ Touching the legacy condition API — deprecated, kept on `/legacy` until v10 (generated code for 8.10 servers imports `Condition` from there)
- 🚫 Exporting anything Condition-based from the root entry
- 🚫 Breaking command result/wait strategy contract
- 🚫 Changing the `WowMetadata` shape — generator reads it
- 🚫 Removing event stream query support
