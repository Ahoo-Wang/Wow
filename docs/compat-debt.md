# Compatibility Debt

Only Wow's public entry APIs wait for a major release to change. Internal implementation does not.

## Scope

A public entry API is what an application, or a client outside the process, is written against:

- the REST API: route paths and methods, parameters, request and response bodies, headers, status and error codes;
- the gateways an application calls: `CommandGateway` and the snapshot and event-stream query gateways;
- the domain programming model: the `wow-api` annotations and contracts, and the DSLs applications and their tests write (the query DSL, the `wow-test` DSL);
- configuration properties (`wow.*`);
- storage and message formats: event streams, snapshots, messages on the bus, wait signals;
- the published TypeScript packages.

Changing or removing a public entry API is a breaking change for a major release. 9.x keeps it compatible: a replaced one is deprecated and keeps working, and v10 removes the deprecated ones together. The TypeScript packages still reach Wow 8.x servers, keep the deprecated Condition API, and keep the names they had in [fetcher](https://github.com/Ahoo-Wang/fetcher); the Kotlin query API and its HTTP endpoints still accept that Condition API. Dropping any of it before v10 is not taken, not even in an `x.Y.0` release.

Everything else is internal implementation, even when its JVM signature is public: the Spring auto-configuration, the WebFlux route factories, handler functions and extractors, the runtime classes behind the gateways. It changes in any `x.Y.0` release without a deprecation cycle and without an entry here. Such a change still updates the module's ABI dump and is marked breaking, so release admission keeps it out of a patch, and its `## Breaking` section tells integrations built on those classes what to use instead. Binary-only shims, JVM declarations kept only so that bytecode compiled against an older 9.x still links, are not kept either: an `x.Y.0` removes them, as 9.3.0 did.

This ledger lists every piece of the public entry debt. Each entry says what is kept compatible, where its markers are, what replaces it, and how v10 removes it.

## Marker Rules

- A deprecated TypeScript API carries `@deprecated` in its doc comment, names the replacement, and ends with `Removed in v10.`
- A deprecated Kotlin API carries `@Deprecated("Scheduled for removal in 10.0.0. Use <replacement>.")`.
- Any other compatibility code carries a line comment `// compat(<scope>): <reason>`:
  - `compat(wow<9)`: kept for Wow 8.x servers, or for code and requests written against the deprecated Condition API.
  - `compat(fetcher)`: kept for names the packages had in fetcher.
  - `compat(wow<9.2)`: kept for code compiled, configuration written, or links sent against Wow 9.1, which 9.2 runs mixed with.
  - `compat(wow<9.3)`: kept for code written or compiled against Wow 9.2.
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

### The Sort Bound On `AggregationQuery`

- **Kept compatible**: `AggregationQuery.MAX_SORT_FIELDS`, released in 9.1.5. The bound applies to every query's `sort`, not only an aggregation's, so it moved to `Sort`; the old name is a deprecated `const` alias of the same value.
- **Markers**: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`
- **Replacement**: `Sort.MAX_FIELDS`.
- **Removal in v10**: delete `AggregationQuery.MAX_SORT_FIELDS`. Code that still reads it stops compiling; `ReplaceWith` points to `Sort.MAX_FIELDS`.

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

### Wow 9.1 Query Annotations

- **Kept compatible**: domain and API jars compiled against 9.1 name the 9.1 annotations, and the JVM silently drops an annotation whose class is missing, so removing them would serve masked fields unmasked. `@Masking`, `MaskStrategy<A>`, `CompiledMask`, `@Mask`, `@KeepMask`, `FullMaskStrategy` and `KeepMaskStrategy` (`me.ahoo.wow.api.query.mask`) and `me.ahoo.wow.api.query.schema.QueryTemporal(timeUnit)` stay. Schema discovery reads `@Mask` as `@Sensitive(SensitivityLevel.DISPLAY)`, `@KeepMask(prefix, suffix)` as `@Sensitive(DISPLAY, mask = Mask(prefix, suffix))`, any other annotation carrying `@Masking` as a `DISPLAY` field masked by its strategy (9.1 let filters and sorts compare the raw value, which `DISPLAY` keeps), and the old `QueryTemporal` as `@QueryTemporal(unit = timeUnit)`.
- **Markers**: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/mask/Masking.kt`, `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QueryTemporal.kt`, `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/LegacyQueryAnnotations.kt`
- **Replacement**: `@Sensitive` with `SensitivityLevel` and `Mask`, and `me.ahoo.wow.api.query.annotation.QueryTemporal(unit, pattern)`.
- **Removal in v10**: delete the two files in `wow-api` and `LegacyQueryAnnotations.kt`; `effectiveMaskRule` reads only `@Sensitive`, `withMember` only the new `@QueryTemporal`, and `MaskRule` loses its `legacy` constructor. A jar still compiled against the old annotations then loses its masking, so the migration guide tells users to recompile against 9.2 first.

### Wow 9.1 HTTP Query Limit Keys

- **Kept compatible**: 9.1 read the HTTP query limits from `wow.webflux.query.{max-list-size, max-page-size, max-page-window, max-filter-nodes, max-filter-values, allow-expensive-operators}`. Each still applies, with a startup warning, where its `wow.query.http.*` key is not set, so an unchanged 9.1 configuration (or one shared by 9.1 and 9.2 nodes) keeps its limits. The configuration metadata marks them deprecated with `deprecation.replacement`.
- **Markers**: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/LegacyHttpQueryKeys.kt`
- **Replacement**: `wow.query.http.*`.
- **Removal in v10**: delete `LegacyHttpQueryKeys.kt`, its call in `QueryAutoConfiguration.queryEntryPolicy` and the six `wow.webflux.query.*` entries in `additional-spring-configuration-metadata.json`.

### Wow 9.1 Query Schema Declaration Location

- **Kept compatible**: 9.1 fell back to `wow-query-schema/{context}/{aggregate}/{model}.json`, in a format 9.2 does not read, and 9.1 nodes may still need the file while 9.1 and 9.2 run side by side. When no source (classpath, working directory or bean) declares that model in 9.2, a file there is logged as a warning naming the 9.2 location and the model uses its inferred schema; `wow.query.schema.legacy-declarations=fail` (`LegacyQuerySchemaDeclarationPolicy.FAIL`) fails the model instead. Beside a 9.2 declaration it is only logged.
- **Markers**: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaSources.kt`, `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/LegacyQuerySchemaDeclarations.kt`, `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaProvider.kt`
- **Replacement**: `META-INF/wow/query-schema/{context}.{aggregate}.{model}.json` and `config/wow/query-schema/…`, in the 9.2 format (the key mapping and the mixed-cluster routes are in the query-model-schema guide).
- **Removal in v10**: delete `legacyResourcePath`, the sources' `legacyDeclarations`, `LegacyQuerySchemaDeclarations.kt` (with `LegacyQuerySchemaDeclarationPolicy` and `wow.query.schema.legacy-declarations`) and the provider's check.

### Wow 9.1 Schema Refresh Routes

- **Kept compatible**: `POST /{aggregate}/snapshot/schema/refresh` and `POST /{aggregate}/event/schema/refresh` (route ids `….snapshot_schema.refresh`, `….event_schema.refresh`), which 9.2 replaced by the `wowQuerySchema` actuator endpoint. Each revalidates its own model of the aggregate through `QuerySchemaCatalog.revalidate(aggregate, model)` (concurrent calls share the reload in flight) and answers as `GET …/schema` (decision D77 of the view engine's design record).
- **Markers**: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/BuiltInHttpRouteHandlerKeys.kt`, `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/snapshot/SnapshotRouteContributor.kt`, `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/event/EventRouteContributor.kt`, `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/QuerySchemaRefreshHandlerFunction.kt`, `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/QueryRouteModule.kt`
- **Replacement**: the `wowQuerySchema` actuator endpoint, and periodic revalidation (`wow.query.schema.revalidate-interval`).
- **Removal in v10**: delete the two route contracts, the `SCHEMA_REFRESH` keys, `QuerySchemaRefreshHandlerFunctionFactory` and its two registrations, then update the OpenAPI snapshots and the route inventory.

### Wow 9.1 Compensation Console Links

- **Kept compatible**: alert messages sent by 9.1 link to the 9.1 console's pages (`/to-retry?id=…`, `/unrecoverable?id=…`, `/active`, …). Each redirects (302) to `/executions` on the system view of the same name, with the same execution open.
- **Markers**: `compensation/wow-compensation-server/src/main/kotlin/me/ahoo/wow/compensation/server/dashboard/DashboardConfiguration.kt`
- **Replacement**: the links 9.2 sends, `/executions?view=system:execution-failed:<view>&id=<id>`.
- **Removal in v10**: delete `legacyNav`. Alerts sent before the 9.2 upgrade then open a 404, so v10's release notes say so.

### Wow 9.2 Aggregate Policies On `@AggregateRoute`

- **Kept compatible**: 9.2 declared whether an aggregate is spaced and its owner policy on its routing annotation, `@AggregateRoute(spaced = …, owner = AggregateRoute.Owner.…)`. 9.3 declares them on the aggregate with `@Spaced` and `@AggregateOwner(OwnerPolicy.…)`; the old attributes and the nested `AggregateRoute.Owner` enum are deprecated but still read, at runtime and by the KSP processor, whenever the new annotation is absent (an attribute counts as declared only when it is not its default, `spaced = true` or `owner != NEVER`). Declaring both with different values fails at startup and at compile time. Code that uses the old type keeps compiling: the `AggregateRouteMetadata` primary constructor and its `owner` property.
- **Markers**: `wow-api/src/main/kotlin/me/ahoo/wow/api/annotation/AggregateRoute.kt`, `wow-core/src/main/kotlin/me/ahoo/wow/modeling/annotation/AggregatePolicyResolver.kt`, `wow-compiler/src/main/kotlin/me/ahoo/wow/compiler/metadata/AggregatePolicyResolver.kt`, `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/metadata/AggregateRouteMetadata.kt`
- **Replacement**: `@Spaced` and `@AggregateOwner(OwnerPolicy.…)` on the aggregate; `AggregateMetadata.spaced` and `AggregateMetadata.owner` for readers; the `OwnerPolicy` constructor and `ownerPolicy` of `AggregateRouteMetadata`.
- **Removal in v10**: delete `spaced`, `owner` and `Owner` from `AggregateRoute` and the legacy branches of both `AggregatePolicyResolver`s (with their conflict checks, which only exist for the old attributes); `AggregateRouteMetadata`'s primary constructor takes `ownerPolicy: OwnerPolicy` in place of `owner`, and its secondary constructor and the `ownerPolicy` getter go. Code still writing the old attributes stops compiling; the migration guide maps `spaced = true` to `@Spaced` and `owner = Owner.X` to `@AggregateOwner(OwnerPolicy.X)`.

### Wow 9.2 `MessageBus.receive`

- **Kept compatible**: 9.2 code that reads a bus as a plain stream with `bus.receive(subscription)`, from Kotlin or Java. 9.3 keeps one entry a bus implements, the abstract `receiver`; `receive` stays a deprecated default member that returns the receiver's messages with processing opened on subscription. Because `receiver` has no default, the two can never default onto each other. A bus that implemented only `receive` must implement `receiver`: that is an SPI change, listed in the migration guide. `DefaultMethodContract` (`test/wow-tck`) skips `MessageBus.receive` by name (`COMPAT_ADAPTERS`; any other deprecated default is still checked), so a decorator need not forward `receive`.
- **Markers**: `wow-core/src/main/kotlin/me/ahoo/wow/messaging/MessageBus.kt`, `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/architecture/DefaultMethodContract.kt`
- **Replacement**: `receiver(subscription).openedMessages()`.
- **Removal in v10**: delete `receive` from `MessageBus` and its `COMPAT_ADAPTERS` entry from `DefaultMethodContract`. Callers stop compiling and use the replacement.

### Wow 9.2 Static `RecoverableExceptionRegistrar`

- **Kept compatible**: in 9.2 `RecoverableExceptionRegistrar` was the process's registry object, so applications called `RecoverableExceptionRegistrar.register(…)`, `unregister(…)` and `getRecoverableType(…)`. 9.3 turned it into the interface providers register into, with `RecoverableExceptionRegistry.DEFAULT` as the process's registry; the interface's companion keeps the three calls, deprecated, delegating to `DEFAULT`. Kotlin source compiles unchanged; Java callers now write `RecoverableExceptionRegistrar.Companion.register(…)` (9.2's object exposed `INSTANCE`), so Java code is better moved to the replacement directly.
- **Markers**: `wow-core/src/main/kotlin/me/ahoo/wow/exception/RecoverableExceptionRegistrar.kt`
- **Replacement**: `RecoverableExceptionRegistry.DEFAULT.register` / `unregister` / `getRecoverableType`, or a `RecoverableExceptionProvider` (`META-INF/services` or a Spring bean).
- **Removal in v10**: delete the companion object. Callers stop compiling and use the replacement.

### Wow 9.5 REST Header Names And `BatchResult` In `wow-openapi`

- **Kept compatible**: 9.6.0 moved the REST wire vocabulary out of `wow-openapi` into `wow-rest-contract` (`me.ahoo.wow.rest`). Application code reads the header names to build or inspect requests, and some returns or reads `BatchResult`, so those keep their old names for one cycle: every constant of `CommandComponent.Header` and of `CommonComponent.Header` (`ERROR_CODE`, `SPACE_ID`) is a deprecated `const` alias of the same value, and `me.ahoo.wow.openapi.BatchResult` is a deprecated `typealias` of `me.ahoo.wow.rest.BatchResult`. The OpenAPI schema name stays `wow.openapi.BatchResult`. The other moved declarations (`RouteSuffixes`, `BuiltInHttpRoutePaths.Global`, `BatchComponent.PathVariable`, the BI script DTOs) are framework-facing and moved without an alias.
- **Markers**: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/command/CommandComponent.kt`, `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/CommonComponent.kt`, `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/BatchResult.kt`
- **Replacement**: `me.ahoo.wow.rest.CommandHeaders`, `me.ahoo.wow.rest.WowHeaders` and `me.ahoo.wow.rest.BatchResult`, in `wow-rest-contract`, which `wow-openapi` and `wow-apiclient` bring.
- **Removal in v10**: delete `CommandComponent.Header`, the two constants of `CommonComponent.Header`, and `wow-openapi`'s `BatchResult.kt`. Callers stop compiling; each `ReplaceWith` names the replacement.

## Held Until v10

Behaviour that is not compatibility code, so it carries no marker, but that 9.x keeps as it is because changing it changes a frozen REST or wire format. v10 changes each one; until then nothing here is touched, not even in an `x.Y.0` release.

- **State routes leave the owner out of their route id and summary.** The aggregate state routes (`ScopeNaming.TENANT_ID_ONLY` in `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/AggregateRouteScope.kt`) name only the tenant scope, even when the path has an owner segment: `GET /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/state` is `example.order.tenant.aggregate.load` and its summary names no scope, while the snapshot and event routes (`TENANT_OWNER`) name both, as `….tenant.owner.….` and `… Within Tenant Owner`. Route ids are a REST contract (`@ahoo-wang/wow-generator` looks operations up by them), and 9.x publishes these ids. v10: name the state routes with `TENANT_OWNER`, delete `TENANT_ID_ONLY`, update the OpenAPI and contract snapshots and the generated clients, and list the renamed operation ids in the release notes.
