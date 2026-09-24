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

### Deprecated Condition API

- **Kept compatible**: the Condition query model of `@ahoo-wang/wow-client`: `Condition`, `ConditionOptions`, `ConditionCapable` and the builder functions (`and`, `eq`, `aggregateId`, `raw`, …); the `Operator` enum and its operator sets; the `OperatorLocale` type and the `en_US` / `zh_CN` locales (package subpaths `./query/locale/en_US` and `./query/locale/zh_CN`); the Condition-based `Queryable`, `SingleQuery`, `ListQuery` and `PagedQuery` and the `singleQuery` / `listQuery` / `pagedQuery` overloads that take a `condition`. Wow before 8.11.0 understands only this model. `raw()` and `Operator.RAW` reach only servers before Wow #2999 (8.11.0); current servers answer them with 400.
- **Markers**: `typescript/wow-client/src/query/condition.ts`, `typescript/wow-client/src/query/operator.ts`, `typescript/wow-client/src/query/locale/operatorLocale.ts`, `typescript/wow-client/src/query/locale/en_US.ts`, `typescript/wow-client/src/query/locale/zh_CN.ts`, `typescript/wow-client/src/query/queryable.ts`
- **Replacement**: `FilterExpression` built with `filter.*` (`filter.and`, `filter.eq`, `filter.aggregateId`, …), `FilterOperator`, and `FilterQueryable`, `FilterSingleQuery`, `FilterListQuery`, `FilterPagedQuery`. `raw()` has no replacement. The locales have none in `wow-client`; applications label `FilterOperator` values themselves.
- **Removal in v10**:
  1. Move `DeletionState`, which is not deprecated and which `filter.ts` imports, out of `condition.ts`.
  2. Delete `condition.ts`, `operator.ts` and `locale/`; remove the two locale subpaths from `package.json` `exports` and from the build entries.
  3. Delete the Condition-based query types and overloads in `queryable.ts`. `FilterListQuery` defaults `limit` to 0 while `ListQuery` defaulted to `DEFAULT_PAGINATION.size`; say so in the migration guide.
  4. Do the entries below that use `Condition` in the same release.

### Condition In Non-Deprecated Signatures

- **Kept compatible**: APIs that are not deprecated but still accept or send a `Condition`, so that code written against the Condition API keeps compiling and Wow before 8.11 keeps working:
  - `count()` of `QueryApi`, `SnapshotQueryClient` and `EventStreamQueryClient` takes `FilterExpression | Condition`;
  - `SnapshotQueryClient.getById()` and `getStateById()` send `condition: aggregateId(id)`;
  - `useCountQuery` and `useFetcherCountQuery` in `@ahoo-wang/wow-react` default their query type to `Condition` and keep a `Condition` overload.
- **Markers**: `typescript/wow-client/src/query/queryApi.ts`, `typescript/wow-client/src/query/snapshot/snapshotQueryClient.ts`, `typescript/wow-client/src/query/event/eventStreamQueryClient.ts`, `typescript/wow-react/src/useCountQuery.ts`, `typescript/wow-react/src/fetcher/useFetcherCountQuery.ts`
- **Replacement**: `FilterExpression` everywhere; `getById()` and `getStateById()` send `filter.aggregateId(id)`, as `getByIds()` already does.
- **Removal in v10**: narrow the parameters to `FilterExpression`, switch the two `getById` methods to `filter`, make `FilterExpression` the default query type of the two hooks and delete their `Condition` overloads. Code passing a `Condition` stops compiling; the migration guide points to `filter.*`.

### Generated Code Uses The Condition API

- **Kept compatible**: `wow-generator` maps the server schemas `wow.api.query.Condition`, `ConditionOptions`, `ListQuery`, `Operator` and `PagedQuery` to the deprecated `wow-client` types of the same names, so code users have already generated uses the deprecated API.
- **Markers**: `typescript/wow-generator/src/model/wowTypeMapping.ts`
- **Replacement**: map the query schemas to `FilterExpression`, `FilterListQuery`, `FilterPagedQuery` and `FilterOperator`, and drop the `Condition` and `ConditionOptions` mappings.
- **Removal in v10**: change the mapping and the generator's expected snapshots. Users must regenerate their clients with `wow-generator` 10; the migration guide says so first, because code generated by 9.x no longer compiles against `wow-client` 10.

### LogicalField Alias

- **Kept compatible**: `LogicalField`, the old name of `QueryField`, in `filter.ts`. `QueryField` itself is current and stays.
- **Markers**: `typescript/wow-client/src/query/filter.ts`
- **Replacement**: `QueryField`.
- **Removal in v10**: delete the alias.

### Wow 8.x Query Fields In The Generator

- **Kept compatible**: servers before Wow 8.11.1 do not publish `x-wow-query-fields` on the snapshot count request body. The generator then reads the query fields from the `field` property of the Condition schema (Ahoo-Wang/fetcher#1359).
- **Markers**: `typescript/wow-generator/src/aggregate/aggregateResolver.ts`
- **Replacement**: `x-wow-query-fields`, which every 9.x server publishes.
- **Removal in v10**: delete the fallback branch in `AggregateResolver.fields()` and its tests; a missing `x-wow-query-fields` becomes an error that names the minimum server version.

### Wow 8.x Contract Matrix

- **Kept compatible**: the TypeScript contract tests run the client and generated code against published `wow-example-server` images 8.10.8 (Condition only) and 8.11.5 (filters), as fetcher's `generator-test.yml` did. The integration cases that must pass there query through the Condition API. The matrix is the `legacy-contract` job of `.github/workflows/typescript-contract.yml`.
- **Markers**: `.github/workflows/typescript-contract.yml`, `typescript/integration-test/test/wow/cart/cartSnapshotQueryClient.test.ts`, `typescript/integration-test/test/wow/cart/cartEventStreamQueryClient.test.ts`
- **Replacement**: the same-source contract job, which builds the server from this repository.
- **Removal in v10**: drop the 8.x images from the matrix and move the integration cases to `filter.*`.

### fetcher-generator CLI Alias

- **Kept compatible**: `@ahoo-wang/wow-generator` installs its CLI under two names, `wow-generator` and `fetcher-generator` (the `bin` field of `typescript/wow-generator/package.json`), so project scripts that call `fetcher-generator generate` keep working.
- **Markers**: `typescript/wow-generator/src/cli.ts`
- **Replacement**: `wow-generator generate`.
- **Removal in v10**: delete the `fetcher-generator` entry from `bin` and the marker.

### fetcher-generator File Names

- **Kept compatible**: the generator still reads its configuration from `fetcher-generator.config.json` by default and records the files it wrote in `.fetcher-generator.json`, so existing projects keep their configuration and a regeneration still removes files an older run wrote.
- **Markers**: `typescript/wow-generator/src/index.ts`, `typescript/wow-generator/src/utils/sourceFiles.ts`
- **Replacement**: `wow-generator.config.json` and `.wow-generator.json`. Introducing them is additive when the old names stay readable, so it can ship in any 9.x release: write the new manifest, and read the new name first with the old one as a fallback.
- **Removal in v10**: stop reading the old names. A project that still has only `fetcher-generator.config.json` must rename it; the migration guide says so.
