# Compatibility Debt

Wow 9.x keeps compatibility with what it replaced. The TypeScript packages still reach Wow 8.x servers, keep the deprecated Condition API, and keep the names they had in [fetcher](https://github.com/Ahoo-Wang/fetcher). The Kotlin query API and its HTTP endpoints still accept that Condition API. All of it is removed in v10, together, as one breaking release. Dropping any of it earlier is a breaking change that 9.x does not take, not even in an `x.Y.0` release.

This ledger lists every piece of that debt. Each entry says what is kept compatible, where its markers are, what replaces it, and how v10 removes it.

## Marker Rules

- A deprecated TypeScript API carries `@deprecated` in its doc comment, names the replacement, and ends with `Removed in v10.`
- A deprecated Kotlin API carries `@Deprecated("Scheduled for removal in 10.0.0. Use <replacement>.")`.
- Any other compatibility code carries a line comment `// compat(<scope>): <reason>`:
  - `compat(wow<9)`: kept for Wow 8.x servers, or for code and requests written against the deprecated Condition API.
  - `compat(fetcher)`: kept for names the packages had in fetcher.
- `.github/scripts/compat-debt.mjs` (`pnpm check:compat-debt`) runs in the `quality` job of `typescript.yml`. It reads `typescript/*/src` and every Kotlin main source set (`*/src/main/kotlin`), and fails when:
  - a `@deprecated` comment in `typescript/*/src` lacks `Removed in v10.`;
  - a Kotlin `@Deprecated("…")` in `*/src/main/kotlin` lacks `Scheduled for removal in 10.0.0.`;
  - a file in `typescript/*/src` or `*/src/main/kotlin` holds a marker but no entry lists it;
  - an entry lists no file, or lists a file that holds no marker.
- The `quality` job runs only when the TypeScript scope or this ledger changes, so a pull request that changes only Kotlin runs the check locally: `pnpm check:compat-debt`.
- The check matches files, not lines. Whether an entry names the replacement and the removal steps completely is for review.

When you add compatibility code, add its marker and list the file under an entry here in the same pull request. When v10 removes an entry, delete the entry with the code.

## Entries

### The `/legacy` Subpath: Deprecated Condition API

- **Kept compatible**: the Condition query model, which Wow before 8.11.0 is the only one to understand. `@ahoo-wang/wow-client` publishes it on its own subpath, `@ahoo-wang/wow-client/legacy`, and never from the root entry: `Condition`, `ConditionOptions`, `ConditionCapable` and the builder functions (`and`, `eq`, `aggregateId`, `raw`, …); the `Operator` enum and its operator sets; the `OperatorLocale` type and the `en_US` / `zh_CN` locales; the Condition-based `Queryable`, `SingleQuery`, `ListQuery` and `PagedQuery`; the request unions `SingleQueryRequest`, `ListQueryRequest` and `PagedQueryRequest` that the query clients accept; and `singleQuery` / `listQuery` / `pagedQuery` factories that build Condition queries (condition defaulting to `all()`, list limit to `DEFAULT_PAGINATION.size`). The root entry and every default use `FilterExpression`. `raw()` and `Operator.RAW` reach only servers before Wow #2999 (8.11.0); current servers answer them with 400.
- **Markers**: `typescript/wow-client/src/legacy/condition.ts`, `typescript/wow-client/src/legacy/operator.ts`, `typescript/wow-client/src/legacy/queryable.ts`, `typescript/wow-client/src/legacy/locale/operatorLocale.ts`, `typescript/wow-client/src/legacy/locale/en_US.ts`, `typescript/wow-client/src/legacy/locale/zh_CN.ts`
- **Replacement**: the root entry: `FilterExpression` built with `filter.*` (`filter.and`, `filter.eq`, `filter.aggregateId`, …), `FilterOperator`, `FilterQueryable`, `FilterSingleQuery`, `FilterListQuery`, `FilterPagedQuery`, and its `singleQuery` / `listQuery` / `pagedQuery`, which take a `filter` defaulting to `filter.matchAll()`. `raw()` has no replacement. The locales have none in `wow-client`; applications label `FilterOperator` values themselves.
- **Removal in v10**:
  1. Delete `src/legacy/`. `DeletionState`, which it uses, already lives in `src/dsl/deletionState.ts`.
  2. Remove `./legacy` from `package.json` `exports` and from the build entries in `vite.config.ts`; delete `test/surface/legacy.txt` and its entries in `test/publicSurface.test.ts`, `test/fixtures/exports.ts` and `scripts/verify-package.mjs`; delete `test/legacy/`.
  3. Do the entries below that use `Condition` in the same release. The migration guide tells 8.10 users that `wow-client` 10 no longer reaches their server.

### Condition In Non-Deprecated Signatures

- **Kept compatible**: APIs of the root entry and of `wow-react` that are not deprecated but still accept a Condition query from `/legacy`, so that an application talking to a Wow 8.10 server can use them:
  - the query methods of `QueryApi`, `SnapshotQueryApi`, `SnapshotQueryClient` and `EventStreamQueryClient` take `SingleQueryRequest`, `ListQueryRequest` and `PagedQueryRequest`, which admit both the `Filter*` queries and the Condition ones; `count()` takes `FilterExpression | Condition`. The unions are declared in `src/client/query/requests.ts`, the only file outside `src/legacy/` that imports from it (it also re-exports `Condition` for `count()`); `/legacy` re-exports the unions under the same names;
  - the query hooks of `@ahoo-wang/wow-react` default their query type to the `Filter*` query (`FilterExpression` for the count hooks) and keep a second overload for the Condition query.
- **Markers**: `typescript/wow-client/src/client/query/requests.ts`, `typescript/wow-client/src/client/query/queryApi.ts`, `typescript/wow-client/src/client/query/snapshot/snapshotQueryClient.ts`, `typescript/wow-client/src/client/query/event/eventStreamQueryClient.ts`, `typescript/wow-react/src/hooks/useCountQuery.ts`, `typescript/wow-react/src/hooks/useListQuery.ts`, `typescript/wow-react/src/hooks/useListStreamQuery.ts`, `typescript/wow-react/src/hooks/usePagedQuery.ts`, `typescript/wow-react/src/hooks/useSingleQuery.ts`, `typescript/wow-react/src/hooks/useFetcherCountQuery.ts`, `typescript/wow-react/src/hooks/useFetcherListQuery.ts`, `typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts`, `typescript/wow-react/src/hooks/useFetcherPagedQuery.ts`, `typescript/wow-react/src/hooks/useFetcherSingleQuery.ts`, `typescript/wow-react/src/internal/useListStream.ts`
- **Replacement**: `FilterExpression` and the `Filter*` queries everywhere. `getById()` and `getStateById()` already send `filter.aggregateId(id)`.
- **Removal in v10**: narrow the unions in `src/client/query/requests.ts` to `FilterSingleQuery`, `FilterListQuery` and `FilterPagedQuery` and drop its `Condition` re-export; narrow the marked `count()` parameters to `FilterExpression`; delete the hooks' Condition overloads and their `/legacy` imports, and narrow `useListStream`'s query type to `FilterListQuery`. Code passing a Condition stops compiling; the migration guide points to `filter.*`.

### Server-Side Condition: The Kotlin Query Model And Its Wire Format

- **Kept compatible**: the Condition query model in `wow-api`, and the requests that use it. A 9.x server keeps accepting it so that code written against it keeps compiling, and so that a TypeScript client on the `/legacy` subpath (entry above) can still query a 9.x server:
  - the types `Condition` and `Operator`, `Condition.toFilterExpression()`, and the deprecated `condition` constructors of `ListQuery`, `PagedQuery` and `SingleQuery`;
  - on the wire, a list, paged or single query body with `condition` in place of `filter`. `QueryJsonDeserializer` reads it as the WebFlux path before 8.11 did: it strips the properties that path ignored, and only for such a body;
  - a filter object without `op`, which the `FilterExpression` deserializer reads as a Condition. The count route's `HttpQueryGuard.strictCountFilter` is off by default, so a count body that names neither `op` nor `operator` still reads as the Condition `all()` and counts every row.
- **Markers**: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/Condition.kt`, `wow-api/src/main/kotlin/me/ahoo/wow/api/query/Operator.kt`, `wow-api/src/main/kotlin/me/ahoo/wow/api/query/LegacyConditionAdapter.kt`, `wow-api/src/main/kotlin/me/ahoo/wow/api/query/ListQuery.kt`, `wow-api/src/main/kotlin/me/ahoo/wow/api/query/PagedQuery.kt`, `wow-api/src/main/kotlin/me/ahoo/wow/api/query/SingleQuery.kt`, `wow-api/src/main/kotlin/me/ahoo/wow/api/query/QueryJsonDeserializer.kt`, `wow-api/src/main/kotlin/me/ahoo/wow/api/query/FilterExpression.kt`, `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuard.kt`
- **Replacement**: `FilterExpression` and the `filter` property of every query.
- **Removal in v10**: in the same release that deletes the TypeScript `/legacy` subpath, and never before it, because a 9.x client on `/legacy` sends `condition` to a 9.x server.
  1. Delete `Condition.kt`, `Operator.kt` and `LegacyConditionAdapter.kt`, and the `condition` constructors of the three queries.
  2. `QueryJsonDeserializer` reads only `filter`: drop `condition` from the `*QueryJson` inputs and delete the legacy property stripping. The `FilterExpression` deserializer rejects a filter without `op`.
  3. Make the strict count body the only behaviour: delete `strictCountFilter` and the legacy `operator` check of `QueryBodyExtractor`.
  4. Delete the entries below that build on `Condition` (the Kotlin DSL and the API client) in the same release.

### Kotlin Condition DSL And API Client

- **Kept compatible**: the deprecated Kotlin builders and client methods that take a `Condition`:
  - in `wow-query`, the `condition { }` DSL (`ConditionDsl`, `condition(...)` in `Dsl.kt`), the `condition(...)` builders of `QueryableDsl`, and `Condition.count(...)` for the snapshot and event-stream gateways;
  - in `wow-apiclient`, `SnapshotCountQueryApi.count(Condition)` and the `Condition.count` extensions of `ReactiveSnapshotCountQueryApi` and `SynchronousSnapshotCountQueryApi`.
- **Markers**: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/ConditionDsl.kt`, `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/Dsl.kt`, `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/QueryableDsl.kt`, `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/QueryDsl.kt`, `wow-query/src/main/kotlin/me/ahoo/wow/query/event/QueryDsl.kt`, `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SnapshotCountQueryApi.kt`, `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/ReactiveSnapshotCountQueryApi.kt`, `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SynchronousSnapshotCountQueryApi.kt`
- **Replacement**: `filterExpression { }` and `FilterDsl`, the `filter(...)` builders, `FilterExpression.count(...)` and `count(FilterExpression)`.
- **Removal in v10**: with the server-side Condition above, delete `ConditionDsl.kt`, the `condition` builders and every `Condition.count`/`count(Condition)` overload, and their tests. Code that still calls them stops compiling; the migration guide points to `filterExpression { }`.

### Generated Code Uses The Condition API

- **Kept compatible**: `wow-generator` maps the Condition-only server schemas `wow.api.query.Condition`, `ConditionOptions` and `Operator`, and the `ListQuery` and `PagedQuery` schemas of servers before 8.11 (which have no `filter` property), to the `wow-client` types of the same names, imported from `@ahoo-wang/wow-client/legacy` (`WOW_LEGACY_TYPES`, `IMPORT_WOW_LEGACY_PATH`). A `ListQuery` or `PagedQuery` schema that carries `filter` maps to `FilterListQuery` or `FilterPagedQuery` from the root entry.
- **Markers**: `typescript/wow-generator/src/wow/conventions.ts`
- **Replacement**: `FilterExpression`, `FilterListQuery`, `FilterPagedQuery` and `FilterOperator`, from the root entry.
- **Removal in v10**: map `ListQuery` and `PagedQuery` to the `Filter*` queries whatever their properties, drop the `Condition`, `ConditionOptions` and `Operator` mappings, `WOW_LEGACY_TYPES` and `IMPORT_WOW_LEGACY_PATH`, and update the generator's expected snapshots. Code generated from an 8.10 server stops compiling against `wow-client` 10; the migration guide says so first.

### Wow 8.x Query Fields In The Generator

- **Kept compatible**: servers before Wow 8.11.1 do not publish `x-wow-query-fields` on the snapshot count request body. The generator then reads the query fields from the `field` property of the Condition schema (Ahoo-Wang/fetcher#1359).
- **Markers**: `typescript/wow-generator/src/wow/resolveWowModel.ts`
- **Replacement**: `x-wow-query-fields`, which every 9.x server publishes.
- **Removal in v10**: delete the fallback branch in `readFields` of `resolveWowModel` and its tests; a missing `x-wow-query-fields` becomes an error that names the minimum server version.

### Wow 8.x Contract Matrix

- **Kept compatible**: the TypeScript contract tests run the client and generated code against published `wow-example-server` images 8.10.8 (Condition only) and 8.11.5 (filters), as fetcher's `generator-test.yml` did. The matrix is the `legacy-contract` job of `.github/workflows/typescript-contract.yml`; against the 8.x images it generates clients and type-checks them, and does not run the integration cases. The Condition integration cases (`cartSnapshotQueryClient.test.ts`, `cartEventStreamQueryClient.test.ts`) run in the same-source contract job, whose server still accepts Condition queries, beside the `filter.*` cases.
- **Markers**: `.github/workflows/typescript-contract.yml`, `typescript/integration-test/test/wow/cart/cartSnapshotQueryClient.test.ts`, `typescript/integration-test/test/wow/cart/cartEventStreamQueryClient.test.ts`
- **Replacement**: the same-source contract job, which builds the server from this repository.
- **Removal in v10**: drop the 8.x images from the matrix and move the integration cases to `filter.*`.

### fetcher-generator CLI Alias

- **Kept compatible**: `@ahoo-wang/wow-generator` installs its CLI under two names, `wow-generator` and `fetcher-generator` (the `bin` field of `typescript/wow-generator/package.json`), so project scripts that call `fetcher-generator generate` keep working.
- **Markers**: `typescript/wow-generator/src/cli/program.ts`
- **Replacement**: `wow-generator generate`.
- **Removal in v10**: delete the `fetcher-generator` entry from `bin` and the marker.

### fetcher-generator File Names

- **Kept compatible**: `wow-generator` reads its configuration from `wow-generator.config.json` and records the files it wrote in `.wow-generator.json`. When those are absent it still reads the names it had in fetcher: it falls back to `fetcher-generator.config.json` with a deprecation warning, and reads an existing `.fetcher-generator.json` so a regeneration still removes files an older run wrote; it then writes `.wow-generator.json` and deletes the old manifest.
- **Markers**: `typescript/wow-generator/src/input/configuration.ts`, `typescript/wow-generator/src/output/outputStore.ts`
- **Replacement**: `wow-generator.config.json` and `.wow-generator.json`.
- **Removal in v10**: delete `LEGACY_CONFIG_PATH`, `LEGACY_GENERATION_MANIFEST` and the fallbacks that read them. A project that still has only `fetcher-generator.config.json` must rename it, and output last generated before 9.x must be regenerated once with 9.x or cleaned by hand; the migration guide says so.
