# AGENTS.md — @ahoo-wang/fetcher-wow

<!-- This file provides coding agents with context about this package. -->

## Build & Run Commands

```bash
# Build this package
pnpm --filter @ahoo-wang/fetcher-wow build

# Run tests
pnpm --filter @ahoo-wang/fetcher-wow test

# Run a single test file
pnpm --filter @ahoo-wang/fetcher-wow vitest run src/command/commandClient.test.ts

# Lint
pnpm --filter @ahoo-wang/fetcher-wow lint

# Clean
pnpm --filter @ahoo-wang/fetcher-wow clean
```

## Testing

- Vitest with `globals: true` and `@vitest/coverage-v8`
- Test files: `*.test.ts` alongside source files
- Run with `--coverage` flag by default

## Project Structure

```
src/
  index.ts                    — Barrel export
  getPropertyValue.ts         — Utility for dynamic property access
  configuration/
    wowMetadata.ts            — Wow metadata configuration (@wow decorator)
    index.ts
  command/
    commandClient.ts          — Command client for sending CQRS commands
    commandHeaders.ts         — Command-specific HTTP headers
    commandRequest.ts         — Command request builder
    commandResult.ts          — Command result handling (wait strategies)
    types.ts                  — Command type definitions
    index.ts
  query/
    queryApi.ts               — Query API decorator-based definitions
    queryClients.ts           — Query client implementations
    queryable.ts              — Queryable interface
    condition.ts              — Query condition builder
    operator.ts               — Query operators (eq, ne, gt, lt, etc.)
    pagination.ts             — Pagination support
    cursorQuery.ts            — Cursor-based pagination
    sort.ts                   — Sort specifications
    projection.ts             — Field projection
    types.ts                  — Query type definitions
    index.ts
    locale/                   — i18n for query operators
      en_US.ts, zh_CN.ts, operatorLocale.ts
    event/
      domainEventStream.ts          — Domain event stream types
      eventStreamQueryApi.ts        — Event stream query API
      eventStreamQueryClient.ts     — Event stream query client
    snapshot/
      snapshot.ts                   — Snapshot types
      snapshotQueryApi.ts           — Snapshot query API
      snapshotQueryClient.ts        — Snapshot query client
    state/
      loadStateAggregateClient.ts         — Load state aggregate client
      loadOwnerStateAggregateClient.ts    — Load by owner state client
  types/
    abab.ts, bi.ts, common.ts, endpoints.ts, error.ts,
    function.ts, modeling.ts, naming.ts, messaging.ts, index.ts
  stories/                    — Storybook stories
```

### Key Concepts

- **Command Client**: Sends CQRS commands with wait strategies (sent, processed, snapshot)
- **Query Clients**: Type-safe query builders for snapshots, event streams, and state aggregates
- **Condition Builder**: Fluent API for constructing query conditions
- **Wow Metadata**: `@wow()` decorator for configuring Wow-specific service metadata
- **Operator System**: Query operators with locale support for UI rendering

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

- ✅ Adding new query operators
- ✅ Adding new condition types
- ✅ Writing new tests
- ⚠️ Changing command client API — affects react wow hooks and generator output
- ⚠️ Modifying query condition builder — affects viewer filter components
- 🚫 Breaking command result/wait strategy contract
- 🚫 Changing Wow metadata decorator signature
- 🚫 Removing event stream query support
