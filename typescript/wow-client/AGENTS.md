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

This package mirrors Wow's query protocol, and two things hold it there.

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

## Testing

- Vitest with `globals: true` and `@vitest/coverage-v8`
- Test files: `*.test.ts` in a `test/` directory at the package root (mirroring `src/`)
- Run with `--coverage` flag by default

## Project Structure

```
src/
  index.ts                    — Barrel export
  getPropertyValue.ts         — Dynamic nested property access by path
  configuration/
    wowMetadata.ts            — Wow metadata types (WowMetadata, BoundedContext, Aggregate)
    index.ts
  command/
    commandClient.ts          — Command client for sending CQRS commands
    commandHeaders.ts         — Command-specific HTTP headers
    commandRequest.ts         — Command request builder
    commandResult.ts          — Command result handling (wait strategies)
    types.ts                  — Command types (CommandStage, CommandId, BatchResult)
    index.ts
  query/
    filter.ts                 — CURRENT filter API (FilterExpression + filter.* builders)
    aggregation.ts            — Aggregation query API (AggregationQuery + aggregation.*)
    queryApi.ts               — Generic QueryApi (list, paged, cursor, aggregate, count)
    queryClients.ts           — QueryClientFactory + createQueryApiMetadata helpers
    queryable.ts              — Query request shapes (Filter*Query, PagedList)
    deletionState.ts          — DeletionState, which filter.deletion() takes
    cursorQuery.ts            — Forward-only cursor query and CursorPage
    pagination.ts             — Pagination support
    sort.ts                   — Sort specifications
    projection.ts             — Field projection
    queryField.ts             — Internal QueryField path check for filter, sort and projection (not exported)
    types.ts                  — DynamicDocument type aliases
    index.ts
    event/
      domainEventStream.ts          — Domain event stream types
      eventStreamQueryApi.ts        — Event stream query API (no single)
      eventStreamQueryClient.ts     — Event stream query client
      index.ts
    snapshot/
      snapshot.ts                   — Materialized snapshot types
      snapshotQueryApi.ts           — Snapshot query API (+ *State variants)
      snapshotQueryClient.ts        — Snapshot query client
      index.ts
    state/
      loadStateAggregateClient.ts         — Load state aggregate client
      loadOwnerStateAggregateClient.ts    — Load by owner state client
      index.ts
  types/
    abac.ts, common.ts, endpoints.ts, error.ts, function.ts,
    messaging.ts, modeling.ts, naming.ts, bi.ts, index.ts
  legacy/                     — DEPRECATED `@ahoo-wang/wow-client/legacy` entry, removed in v10
    index.ts                  — The entry
    condition.ts              — Condition model and builders
    operator.ts               — Operator enum — use FilterOperator
    queryable.ts              — Condition queries, their factories, and the *QueryRequest unions
    locale/                   — i18n for the Operator enum (en_US, zh_CN)
scripts/
  verify-package.mjs          — Run by the build: entries resolve and export what test/surface/ lists
test/
  surface/                    — The public surface of each entry, one name a line
  publicSurface.test.ts       — Holds the source entries to those lists (-u to accept a change)
```

## Public surface

The root entry (`src/index.ts`) and `/legacy` (`src/legacy/index.ts`) are the
only entries. Their exports are listed name by name under `test/surface/`; a
new export, or a removed one, changes a list, and the change is made on
purpose with `pnpm exec vitest run test/publicSurface.test.ts -u`. The build
runs `scripts/verify-package.mjs`, which holds the built ES module and
CommonJS entries to the same lists. Nothing Condition-based is exported from
the root entry.

### Key Concepts

- **Command Client**: Sends CQRS commands with wait strategies (sent, processed, snapshot)
- **Query Clients**: Type-safe query builders for snapshots, event streams, and state aggregates
- **Filter Expressions**: `filter.*` builders produce the `FilterExpression` tree the filterable `QueryApi` operations take — the current API. It is optional on `AggregationQuery`, and the load-state clients accept no filter at all
- **Aggregation**: `aggregation.*` builds groups, metrics and arithmetic expressions, and `aggregation.query()` admits the assembled query against the rules Wow enforces in its constructor. There is no `aggregation.having()` — `HavingExpression` is constructed directly, and `derived()` takes an already-built `DerivedExpression`
- **Legacy Condition API**: `src/legacy/` is the `@ahoo-wang/wow-client/legacy` entry, for Wow servers before 8.11 — superseded by `filter.ts`. The root entry never exports it; the query clients accept its request shapes through the `*QueryRequest` unions (see `docs/compat-debt.md`)
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

- ✅ Adding filter operators or expression types in `filter.ts`
- ✅ Adding aggregation groups or metrics in `aggregation.ts`
- ✅ Writing new tests
- ⚠️ Changing command client API — affects `wow-react` hooks and `wow-generator` output
- ⚠️ Changing the `FilterExpression` API — view-engine and react build on it
- ⚠️ Touching the legacy condition API — deprecated, kept on `/legacy` until v10 (generated code for 8.10 servers imports `Condition` from there)
- 🚫 Exporting anything Condition-based from the root entry
- 🚫 Breaking command result/wait strategy contract
- 🚫 Changing the `WowMetadata` shape — generator reads it
- 🚫 Removing event stream query support
