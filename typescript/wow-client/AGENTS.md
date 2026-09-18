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

## Wow conformance

This package mirrors Wow's query protocol, so every rule Wow enforces by
throwing is accounted for in `test/query/wowConformance.test.ts`. Each entry
names the rule verbatim, cites its Kotlin source, and says which of three
things this package does with it: mirrors it (with an input that breaks it),
satisfies it by the shape of the builders, or leaves it to the server (with
the reason). Add an entry in the same change as any new rule.

The tests keep those answers honest. They cannot see a rule Wow adds upstream,
so when bumping the Wow version, diff the register against a Wow checkout:

```bash
pnpm --filter @ahoo-wang/fetcher-wow check:wow /path/to/Wow
```

It exits non-zero naming any rule in `wow-api`'s query package the register
does not carry, and any wire value out of step with Wow: every enum, and the
`@JsonSubTypes` names a sealed interface is dispatched on. A value Wow has that
this package lacks is missing; a value this package sends that Wow does not
know is refused with a 400. The comparison runs both ways: every enum here must
have a counterpart in Wow too, so an enum Wow deletes outright is caught — and
so is a parsing failure that silently drops a Kotlin name, which one-way
comparison would report as success. Deliberate differences are listed in the
script with their reason — `JDK_ENUMS` for `TimeUnit`, which Wow takes from the
JDK rather than declaring; `SERVER_ONLY_ENUMS` for schema metadata the client
never sends; `TS_ONLY_VALUES` for `Operator.RAW`, which the deprecated
Condition API keeps for servers older than Wow #2999. It is not part of CI,
which has no Wow checkout.

The checker fails closed: where it recognises an enum entry, a discriminator
or a rule but cannot read it, that is an error, never a silent skip. Every
blind spot it has had took the shape of a dropped name and a reported success,
and comparing both ways only catches a name that goes missing whole, not one
value dropped from a set that otherwise matches. Discriminators spelled as
constants — Wow spells all fifty filter operators as
`QueryProtocol.FilterExpression.Operator.X` — are resolved through the `const
val`s they name, and a constant that is not one whole literal is unreadable. So
is a constant path declared twice: Kotlin tells the two apart by package and
import, which the checker does not read. A value is whole only where its
expression visibly ends, and a line break does not end one — inside brackets
Kotlin reads on across it, and anywhere before a leading `.`.

Failing closed does not catch a value read confidently but wrongly, so the
constructs that change what goes on the wire are read for what they put there:
an entry's `@JsonProperty` value rather than its Kotlin name, and an enum
written through `@JsonValue` is reported, since its entry names are not sent.
Two declarations sharing a simple name are an error rather than one silently
replacing the other. Each `JsonSubTypes.Type` must yield a name — from
`name =`, `names =`, or a single `@JsonTypeName` on its class — and its owner
must carry `@JsonTypeInfo(use = …Id.NAME)`, since under any other id the wire
does not carry those names. Jackson's annotations are recognised by their
qualified names as well as their short ones, `@JsonValue` under any use-site
target too, and one imported under an alias is an error, since what it declares
would otherwise be skipped whole. Comments follow each language's own rules:
Kotlin block comments nest, TypeScript's do not. Declarations, and the `use` of
a `@JsonTypeInfo`, are located in a copy of the source with comments and
strings blanked, so a documentation example or a setting kept in a comment is
neither mistaken for the real one nor raises a false alarm, and then read from
the original at the same offsets. This package's enum members are split
outside strings, so a value holding a comma or a brace reads whole.

`test/fixtures/` holds the stand-ins: `wow-synthetic` for most shapes,
`wow-kdoc` for declarations that exist only in comments and strings, and
`wow-ts-comments` with `ts-comments` as a pair for how this package's own
TypeScript is read, which `WOW_CONFORMANCE_TS_SOURCE` points the checker at.

The checker is itself held by `conformanceScript.test.ts` against the stand-in
checkout in `test/fixtures/wow-synthetic`, one case per shape it must read.
When it misses something, add the shape there first.

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
    queryField.ts             — Internal QueryField path check for filter, sort and projection (not exported)
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
scripts/
  check-wow-conformance.mjs   — Diffs the conformance register against a Wow checkout (see Wow conformance)
```

### Key Concepts

- **Command Client**: Sends CQRS commands with wait strategies (sent, processed, snapshot)
- **Query Clients**: Type-safe query builders for snapshots, event streams, and state aggregates
- **Filter Expressions**: `filter.*` builders produce the `FilterExpression` tree the filterable `QueryApi` operations take — the current API. It is optional on `AggregationQuery`, and the load-state clients accept no filter at all
- **Aggregation**: `aggregation.*` builds groups, metrics and arithmetic expressions, and `aggregation.query()` admits the assembled query against the rules Wow enforces in its constructor. There is no `aggregation.having()` — `HavingExpression` is constructed directly, and `derived()` takes an already-built `DerivedExpression`
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
