# AGENTS.md — @ahoo-wang/fetcher-wow

<!-- This file provides coding agents with context about this package. -->

## Build & Run Commands

```bash
# Build this package
pnpm --filter @ahoo-wang/fetcher-wow build

# Run tests
pnpm --filter @ahoo-wang/fetcher-wow test

# Run a single test file
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/command/commandHttpHeaders.test.ts

# Lint
pnpm --filter @ahoo-wang/fetcher-wow lint

# Clean
pnpm --filter @ahoo-wang/fetcher-wow clean
```

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
    cursorQuery.ts            — Forward-only cursor query and CursorPage
    pagination.ts             — Pagination support
    sort.ts                   — Sort specifications
    projection.ts             — Field projection
    types.ts                  — DynamicDocument type aliases
    condition.ts              — DEPRECATED legacy conditions; DeletionState still current
    operator.ts               — DEPRECATED legacy Operator enum — use FilterOperator
    index.ts
    locale/                   — DEPRECATED i18n for the legacy Operator enum
      en_US.ts, zh_CN.ts, operatorLocale.ts
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
```

### Key Concepts

- **Command Client**: Sends CQRS commands with wait strategies (sent, processed, snapshot)
- **Query Clients**: Type-safe query builders for snapshots, event streams, and state aggregates
- **Filter Expressions**: `filter.*` builders produce the `FilterExpression` tree the filterable `QueryApi` operations take — the current API. It is optional on `AggregationQuery`, and the load-state clients accept no filter at all
- **Aggregation**: `aggregation.*` builds groups, metrics and arithmetic expressions. There is no `aggregation.having()` — `HavingExpression` is constructed directly, and `derived()` takes an already-built `DerivedExpression`
- **Legacy Condition API**: `condition.ts`, `operator.ts`, and `locale/` are deprecated — superseded by `filter.ts`. The exception is `DeletionState`, which is not deprecated and is what `filter.deletion()` takes
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
- Version synced via `pnpm update-version`

## Boundaries

- ✅ Adding filter operators or expression types in `filter.ts`
- ✅ Adding aggregation groups or metrics in `aggregation.ts`
- ✅ Writing new tests
- ⚠️ Changing command client API — affects react wow hooks and generator output
- ⚠️ Changing the `FilterExpression` API — view-engine and react build on it
- ⚠️ Touching the legacy condition API — viewer still builds on it
- 🚫 Breaking command result/wait strategy contract
- 🚫 Changing the `WowMetadata` shape — generator reads it
- 🚫 Removing event stream query support
