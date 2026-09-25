# Compatibility Debt

Wow 9.x keeps the TypeScript packages compatible with what they replaced: Wow 8.x servers, the deprecated Condition API, and the names the packages had in [fetcher](https://github.com/Ahoo-Wang/fetcher). All of it is removed in v10, together, as one breaking release. Dropping any of it earlier is a breaking change that 9.x does not take, not even in an `x.Y.0` release.

This ledger lists every piece of that debt. Each entry says what is kept compatible, where its markers are, what replaces it, and how v10 removes it.

## Marker Rules

- A deprecated API carries `@deprecated` in its doc comment, names the replacement, and ends with `Removed in v10.`
- Any other compatibility code carries a line comment `// compat(<scope>): <reason>`:
  - `compat(wow<9)`: kept for Wow 8.x servers or for code written against the deprecated Condition API.
  - `compat(fetcher)`: kept for names the packages had in fetcher.
- `.github/scripts/compat-debt.mjs` runs in the `quality` job of `typescript.yml`. It fails when:
  - a `@deprecated` comment in `typescript/*/src` lacks `Removed in v10.`;
  - a file in `typescript/*/src` holds a marker but no entry lists it;
  - an entry lists no file, or lists a file that holds no marker.
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
- **Markers**: `typescript/wow-client/src/client/query/requests.ts`, `typescript/wow-client/src/client/query/queryApi.ts`, `typescript/wow-client/src/client/query/snapshot/snapshotQueryClient.ts`, `typescript/wow-client/src/client/query/event/eventStreamQueryClient.ts`, `typescript/wow-react/src/hooks/useCountQuery.ts`, `typescript/wow-react/src/hooks/useListQuery.ts`, `typescript/wow-react/src/hooks/useListStreamQuery.ts`, `typescript/wow-react/src/hooks/usePagedQuery.ts`, `typescript/wow-react/src/hooks/useSingleQuery.ts`, `typescript/wow-react/src/hooks/useFetcherCountQuery.ts`, `typescript/wow-react/src/hooks/useFetcherListQuery.ts`, `typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts`, `typescript/wow-react/src/hooks/useFetcherPagedQuery.ts`, `typescript/wow-react/src/hooks/useFetcherSingleQuery.ts`
- **Replacement**: `FilterExpression` and the `Filter*` queries everywhere. `getById()` and `getStateById()` already send `filter.aggregateId(id)`.
- **Removal in v10**: narrow the unions in `src/client/query/requests.ts` to `FilterSingleQuery`, `FilterListQuery` and `FilterPagedQuery` and drop its `Condition` re-export; narrow the marked `count()` parameters to `FilterExpression`; delete the hooks' Condition overloads and their `/legacy` imports. Code passing a Condition stops compiling; the migration guide points to `filter.*`.

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
