# QueryModelSchema Physical Binding Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the split string-based field conversion layer and make `QueryFieldBinding.physicalField` the single source for MongoDB and Elasticsearch physical query paths.

**Architecture:** Keep the existing `ResolvedQuery(query, schema)` boundary. Add one public `QueryModelSchema.resolvePhysicalField(...)` behavior that reuses the existing field resolver and returns the binding physical path or the original path for an accepted `COMPATIBLE` field. Backend-specific `*Compiler` classes consume that behavior and emit BSON or Elasticsearch DSL; no generic Converter interface or duplicated Query AST is introduced.

**Tech Stack:** Kotlin 2.4.10, JVM 17, Gradle, Reactor, JUnit Jupiter, MockK, FluentAssert, MongoDB Reactive Streams, Elasticsearch Java Client, Jackson.

**Spec:** `documentation/docs/zh/guide/query/query-model-schema-physical-binding-design.md`

## Global Constraints

- Project version is `9.0.8`; keep Query JSON, Schema HTTP JSON, storage layout, and Cursor wire format unchanged.
- The user approved source and binary breaking changes for these internal implementation APIs; do not keep deprecated bridges, aliases, compatibility constructors, or overloads.
- Keep `ResolvedQuery(query, schema)` unchanged; do not add `physicalQuery`, Planner, PreparedQuery, or another top-level query abstraction.
- `QueryFieldBinding.physicalField` is the only physical binding source after Schema materialization.
- A field already accepted as `COMPATIBLE` because metadata is unavailable must compile using its original logical path; a field resolved as `INCOMPATIBLE` is rejected before Backend execution.
- MongoDB Snapshot maps `aggregateId` to `_id`; MongoDB EventStream maps `id` to `_id`; EventStream `aggregateId` remains `aggregateId`.
- `QueryFieldSchema.projectionField` is the physical projection path. MongoDB must materialize it from the `PRESENCE` binding; Elasticsearch keeps its mapping-derived `projectionPath`.
- Use FluentAssert `.assert()` in Kotlin tests, preserve Reactor non-blocking paths, and add regression tests for every behavior changed.
- Do not add dependencies, change Gradle module structure, modify generated clients, or add files under `docs/superpowers`.
- Use `apply_patch` for source edits and `git mv` for file renames. Run the narrowest affected Gradle test/check after each task.

---

### Task 1: Add the cross-module physical field resolution behavior

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFieldSchemaResolver.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt`

**Interfaces:**
- Produces the public implementation API:

```kotlin
fun QueryModelSchema.resolvePhysicalField(
    field: QueryField,
    capability: QueryCapability,
    logicalParent: QueryField? = null,
    resolvedParent: QueryField? = null,
    physicalParent: QueryField? = null,
): QueryField
```

- The method must reuse `QueryFieldSchemaResolver.resolve(...)`, return `physicalField` when a binding exists, convert it to a relative path when `physicalParent` is supplied, and fall back to the absolute logical field for a missing field or missing binding that the Gateway has already accepted as `COMPATIBLE`.
- Do not expose `QueryFieldResolution`, add a second resolver interface, or calculate capability compatibility in this method.

- [ ] **Step 1: Add failing tests for exact and fallback physical paths**

Add tests that construct a `QueryModelSchema` with a `SORT` or `EXACT_MATCH` binding whose `resolvedField` and `physicalField` differ, then assert:

```kotlin
schema.resolvePhysicalField(field, QueryCapability.SORT)
    .assert().isEqualTo(physicalField)
```

Add a missing-field case that asserts the original `QueryField` is returned. Add a nested case with an absolute binding and a physical parent that asserts only the relative child path is returned. Use the existing test schema helpers and FluentAssert.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QueryModelSchemaTest" --tests "me.ahoo.wow.query.schema.QuerySchemaResolverTest"
```

Expected: test compilation fails because `resolvePhysicalField` is absent.

- [ ] **Step 3: Implement the smallest Schema behavior**

Keep one `QueryFieldSchemaResolver` owned by `QueryModelSchema` and delegate to its existing parent-aware resolution. For the fallback, use the resolver's absolute logical field, not a backend-specific string mapping. For a physical parent, call `relativeTo(physicalParent)` and throw `QuerySchemaValidationException` if a present binding cannot produce a relative path.

- [ ] **Step 4: Run the focused tests and check**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QueryModelSchemaTest" --tests "me.ahoo.wow.query.schema.QuerySchemaResolverTest"
./gradlew :wow-query:check
```

Expected: PASS, with no changes to public Query JSON or Schema metadata serialization.

- [ ] **Step 5: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFieldSchemaResolver.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaTest.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt
git commit -m "refactor(query): centralize physical field resolution"
```

### Task 2: Materialize MongoDB physical bindings without FieldConverter

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapter.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapterTest.kt`

**Interfaces:**
- `MongoQuerySchemaAdapter` no longer accepts `FieldConverter`.
- The adapter owns only the model-specific materialization rule needed before `QueryModelSchema` exists:

```text
SNAPSHOT + aggregateId -> _id
EVENT_STREAM + id      -> _id
otherwise              -> logical path
```

- The resulting `QueryFieldBinding` contains the physical path, and `projectionField` is set to that physical path.

- [ ] **Step 1: Replace converter-based adapter tests with binding contract tests**

Update `MongoQuerySchemaAdapterTest` to remove injected `FieldConverter` fixtures. Add assertions for:

```text
Snapshot aggregateId binding.physicalField == _id
EventStream id binding.physicalField == _id
EventStream aggregateId binding.physicalField == aggregateId
Snapshot projectionField for aggregateId == _id
Unknown COMPATIBLE field fallback remains its logical path
```

Keep validator/index facts in the fixture and assert that the adapter looks them up by the same physical path written into the binding.

- [ ] **Step 2: Run the adapter tests and verify RED**

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapterTest"
```

Expected: compilation fails because the tests still reference the removed constructor parameter and the expected projection path is still logical.

- [ ] **Step 3: Remove the adapter FieldConverter parameter**

Delete the `fieldConverter` constructor parameter and the companion `bind` overload parameter. Add one private model-aware physical-path function and use it consistently for:

1. `storageSchemas` lookup;
2. `QueryFieldBinding.physicalField`;
3. `QueryFieldSchema.projectionField`.

Do not create a replacement converter interface. Keep capability discovery, validator proof, invalid-container handling, and dynamic-child logic unchanged.

- [ ] **Step 4: Run the adapter tests and Mongo schema check**

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapterTest"
./gradlew :wow-mongo:check
```

Expected: PASS; no adapter production source imports `FieldConverter`.

- [ ] **Step 5: Commit**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapter.kt \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapterTest.kt
git commit -m "refactor(mongo): materialize physical query bindings"
```

### Task 3: Rename and make Mongo filter compilation Schema-aware

**Files:**
- Rename: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoFilterConverter.kt` to `AbstractMongoFilterCompiler.kt`
- Rename: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/SnapshotFilterConverter.kt` to `SnapshotFilterCompiler.kt`
- Rename: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/EventStreamFilterConverter.kt` to `EventStreamFilterCompiler.kt`
- Test rename: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/SnapshotFilterConverterTest.kt` to `SnapshotFilterCompilerTest.kt`
- Test rename: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/event/EventStreamFilterConverterTest.kt` to `EventStreamFilterCompilerTest.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoFilterCompiler.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/SnapshotFilterCompiler.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/EventStreamFilterCompiler.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/SnapshotFilterCompilerTest.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/event/EventStreamFilterCompilerTest.kt`

**Interfaces:**
- Replace the public execution methods with:

```kotlin
fun compile(filter: FilterExpression, schema: QueryModelSchema): Bson
internal fun compileWithoutDefaultDeletion(
    filter: FilterExpression,
    schema: QueryModelSchema,
    logicalParent: QueryField? = null,
    physicalParent: QueryField? = null,
): Bson
```

- Remove `fieldConverter`, `convertField`, and all string path conversion.
- Keep normalizer defaults and all Mongo operator/value behavior unchanged.

- [ ] **Step 1: Rename tests and change their expected entry point**

Use `git mv` for the two test files, rename test classes and imports, and change direct calls from `convert(...)` to `compile(..., schema)`. Calls that previously supplied a string parent must supply the logical and physical parent arguments of `compileWithoutDefaultDeletion`. Build identity `QueryModelSchema` fixtures with the system bindings instead of injecting `FieldConverter`.

- [ ] **Step 2: Add failing physical-path regression tests**

Add or retain tests for:

- Snapshot `AggregateIdFilter` compiling against `_id`;
- EventStream `IdFilter`/`IdsFilter` compiling against `_id`;
- EventStream `AggregateIdFilter` compiling against `aggregateId`;
- a normal field filter using its `EXACT_MATCH` physical binding;
- an accepted missing/dynamic field using its original path;
- nested `ElementMatchFilter` using a relative physical child path exactly once.

- [ ] **Step 3: Run the renamed filter tests and verify RED**

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.SnapshotFilterCompilerTest" --tests "me.ahoo.wow.mongo.query.event.EventStreamFilterCompilerTest"
```

Expected: compilation fails until the compiler accepts Schema and the old converter property is removed.

- [ ] **Step 4: Implement capability-aware field compilation**

Rename the class and entry points. Replace the current `parent`/`mapField` string conversion with a small internal scope carrying the absolute logical and physical Element parents:

```kotlin
private data class FilterScope(
    val logicalParent: QueryField? = null,
    val physicalParent: QueryField? = null,
)
```

Each field operator calls `schema.resolvePhysicalField` with its capability; `ElementMatchFilter` resolves the container with `ELEMENT_SCOPE`, then compiles its predicate relative to the container physical path without a second mapping.

Compile semantic identity filters through the model binding:

```text
IdFilter/IdsFilter -> id for EVENT_STREAM, aggregateId otherwise
AggregateIdFilter/AggregateIdsFilter -> aggregateId
```

Keep `Documents.ID_FIELD` only where Mongo’s document identity is explicitly required by the storage contract, not as a replacement for logical field resolution. Preserve `defaultDeletionState` behavior.

- [ ] **Step 5: Run filter tests and check**

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.SnapshotFilterCompilerTest" --tests "me.ahoo.wow.mongo.query.event.EventStreamFilterCompilerTest"
./gradlew :wow-mongo:check
```

Expected: PASS, including nested Element scope and model-specific identity tests.

- [ ] **Step 6: Commit**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query
git commit -m "refactor(mongo): compile filters from schema bindings"
```

### Task 4: Replace Mongo projection and sort converters with compilers

**Files:**
- Rename: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoProjectionConverter.kt` to `MongoProjectionCompiler.kt`
- Rename: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoSortConverter.kt` to `MongoSortCompiler.kt`
- Test rename: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoProjectionConverterTest.kt` to `MongoProjectionCompilerTest.kt`
- Test rename: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoSortConverterTest.kt` to `MongoSortCompilerTest.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoProjectionCompiler.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoSortCompiler.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoProjectionCompilerTest.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoSortCompilerTest.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCollections.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt`

**Interfaces:**
- `MongoProjectionCompiler.compile(projection, schema): Bson?` remains Schema-aware and emits exact Mongo projection paths.
- `MongoProjectionCompiler.cursorProjection(projection, sortFields, schema)` keeps the existing cursor projection contract.
- `MongoSortCompiler.compile(sort, schema): Bson?` emits Mongo sort syntax after resolving every field with `QueryCapability.SORT`.
- `MongoSortCompiler.physicalField(field, schema): String` is the only cursor-facing physical sort helper; it replaces `convertField`.

- [ ] **Step 1: Rename tests and replace custom converters with Schema bindings**

Use `git mv` for both tests, rename classes, remove `FieldConverter` imports, and express prefix/identity expectations as `QueryFieldBinding.physicalField` fixtures. Add assertions that projection and sort use the same physical path as the Schema binding.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.MongoProjectionCompilerTest" --tests "me.ahoo.wow.mongo.query.MongoSortCompilerTest"
```

Expected: compilation fails until the renamed classes and new Schema signatures exist.

- [ ] **Step 3: Implement the concrete Mongo compilers**

Remove both abstract base classes and their `FieldConverter` properties. In the projection compiler, use the Schema field’s physical `projectionField` and fall back to the request field for an accepted unknown field. Preserve include/exclude BSON behavior and cursor-internal field handling. In the sort compiler, call `schema.resolvePhysicalField` with `SORT` for each sort item and preserve direction/order behavior.

- [ ] **Step 4: Wire all Mongo find and cursor paths**

Update `MongoCollections.findDocument`, `AbstractMongoQueryBackend`, and their callers so every filter, projection, sort, count, and cursor operation passes the same non-null `QueryModelSchema` to the relevant compiler. Compute one physical sort list per cursor execution; use it for Mongo continuation filters, storage sort, `valueAt`, and internal-field removal while retaining the logical query for public cursor semantics.

- [ ] **Step 5: Run Mongo compiler and backend tests**

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.MongoProjectionCompilerTest" \
  --tests "me.ahoo.wow.mongo.query.MongoSortCompilerTest" \
  --tests "me.ahoo.wow.mongo.query.AbstractMongoQueryBackendTest" \
  --tests "me.ahoo.wow.mongo.query.MongoCursorDocumentsTest"
./gradlew :wow-mongo:check
```

Expected: PASS; no Mongo find/cursor code calls a field converter or maps a field by string.

- [ ] **Step 6: Commit**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query
git commit -m "refactor(mongo): compile projection and sort from schema"
```

### Task 5: Migrate Mongo aggregation, Backend wiring, and factories

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCollections.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackendFactory.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackendFactory.kt`
- Modify: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackendTest.kt`

**Interfaces:**
- `MongoAggregationCompiler` continues to receive `QueryModelSchema` and a Mongo filter compiler, but it no longer receives or calls `FieldConverter`/`convertField`.
- Snapshot/EventStream Backend properties use `SnapshotFilterCompiler`, `EventStreamFilterCompiler`, `MongoProjectionCompiler`, and `MongoSortCompiler`.
- Factories construct those concrete compilers without field-converter arguments and construct `MongoQuerySchemaAdapter` without a converter.

- [ ] **Step 1: Add aggregation regression tests for Schema-only physical paths**

Update `MongoAggregationCompilerTest` fixtures to encode physical paths in `QueryFieldBinding`. Add assertions for Snapshot identity aggregation, EventStream fields, temporal aggregation paths, and an accepted dynamic/COMPATIBLE path that remains unchanged. Keep tests for unsupported capabilities failing with `QuerySchemaValidationException`.

- [ ] **Step 2: Run the aggregation tests and verify RED**

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest"
```

Expected: compilation fails because the test fixtures and compiler still reference the old filter converter and fallback method.

- [ ] **Step 3: Remove aggregation converter fallback**

Pass the Schema to the renamed filter compiler for the root and element filters. Replace `converter.convertField` in the field resolver with `schema.resolvePhysicalField` or the original accepted logical path according to the capability result. Preserve logical/physical parent tracking for nested elements and preserve storage-type/temporal validation.

- [ ] **Step 4: Wire Backend operations and factories**

Update all Abstract Mongo Backend methods so:

- `executeSingle`, `executeList`, and `executePaged` pass Schema to filter/projection/sort compilers;
- `executeCount` accepts Schema and compiles the filter with it;
- `executeCursor` uses `MongoSortCompiler.physicalField` and Schema-aware filter/projection compilation;
- `executeAggregation` constructs `MongoAggregationCompiler` with the renamed filter compiler.

Remove converter imports and constructor arguments from Snapshot/EventStream concrete Backends and Factories. Do not change Provider routing or Backend Binding behavior.

- [ ] **Step 5: Run all Mongo query tests**

Run:

```bash
./gradlew :wow-mongo:test
./gradlew :wow-mongo:check
```

Expected: PASS; `rg "FieldConverter|convertField" wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query` returns no field-mapping references, while `MongoCursorFilterCompiler` remains.

- [ ] **Step 6: Commit**

```bash
git add wow-mongo/src/main wow-mongo/src/test
git commit -m "refactor(mongo): remove runtime field conversion"
```

### Task 6: Rename Elasticsearch compilers and remove shared Converter APIs

**Files:**
- Rename: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchFilterConverter.kt` to `AbstractElasticsearchFilterCompiler.kt`
- Rename: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/EventStreamFilterConverter.kt` to `EventStreamFilterCompiler.kt`
- Rename: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/SnapshotFilterConverter.kt` to `SnapshotFilterCompiler.kt`
- Rename: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchProjectionConverter.kt` to `ElasticsearchProjectionCompiler.kt`
- Rename: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchSortConverter.kt` to `ElasticsearchSortCompiler.kt`
- Rename tests: `ElasticsearchProjectionConverterTest.kt`, `ElasticsearchSortConverterTest.kt`, `ElasticsearchFilterConverterTest.kt`, `event/EventStreamFilterConverterTest.kt` to matching `*CompilerTest.kt` names
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackend.kt`
- Modify: all renamed Elasticsearch tests and imports
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/FieldConverter.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/ProjectionConverter.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/SortConverter.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/AbstractProjectionConverter.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/AbstractSortConverter.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/FieldConverterTest.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/ProjectionConverterTest.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/SortConverterTest.kt`

**Interfaces:**
- Elasticsearch concrete types expose `compile`/`toSourceFilter`/`toSortOptions` behavior without implementing `ProjectionConverter` or `SortConverter`.
- `AbstractElasticsearchFilterCompiler` and its Snapshot/EventStream products use `compile` naming; preserve existing document-id and aggregate-id semantics.
- No generic converter interface remains in `wow-query`.

- [ ] **Step 1: Rename Elasticsearch production and test files**

Use `git mv`, update class/object names, imports, extension receiver names, and test display names. Do not alter Elasticsearch mapping or nested-query behavior in this step.

- [ ] **Step 2: Run Elasticsearch tests and verify RED**

Run:

```bash
./gradlew :wow-elasticsearch:test
```

Expected: compilation fails only at references to the old Converter names and deleted generic interfaces.

- [ ] **Step 3: Make the concrete compilers independent**

Remove `ProjectionConverter`/`SortConverter` supertypes and the two abstract query converter bases. Keep `ElasticsearchProjectionCompiler` Schema-aware for physical `projectionField`; keep `ElasticsearchSortCompiler`’s direct physical sort behavior; rename filter `convert` calls to `compile` without changing generated DSL.

- [ ] **Step 4: Delete the shared Converter files and tests**

Delete only the files listed above. Update every remaining production/test/benchmark import through repository-wide search. Do not delete backend-native compiler classes or `MongoCursorFilterCompiler`.

- [ ] **Step 5: Run Elasticsearch checks**

Run:

```bash
./gradlew :wow-elasticsearch:test
./gradlew :wow-elasticsearch:check
```

Expected: PASS; no Elasticsearch production source imports `ProjectionConverter` or `SortConverter`.

- [ ] **Step 6: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/converter \
  wow-query/src/test/kotlin/me/ahoo/wow/query/converter \
  wow-elasticsearch/src/main wow-elasticsearch/src/test
git commit -m "refactor(query): replace converters with backend compilers"
```

### Task 7: Update documentation and perform repository-wide verification

**Files:**
- Modify: `documentation/docs/en/guide/query/query-model-schema.md`
- Modify: `documentation/docs/en/guide/query/query-backend.md`
- Modify: `documentation/docs/zh/guide/query/query-model-schema.md`
- Modify: `documentation/docs/zh/guide/query/query-model-schema-phase-zero-design.md`
- Modify: `documentation/docs/zh/guide/query/query-backend.md`
- Modify: `documentation/docs/zh/guide/query/query-model-schema-physical-binding-design.md`
- Modify: any remaining tracked source/test/benchmark file reported by the repository-wide search

- [ ] **Step 1: Remove obsolete documentation claims**

Replace the statement that MongoDB maps logical fields through `FieldConverter` with the Schema binding contract. Document that `QueryFieldBinding.physicalField` is the only physical path source, `projectionField` is physical, and accepted missing metadata falls back to the original path. Mark the physical-binding design document as implemented.

- [ ] **Step 2: Search for stale APIs and mapping calls**

Run:

```bash
rg -n "FieldConverter|ProjectionConverter|SortConverter|AbstractProjectionConverter|AbstractSortConverter|AbstractMongoFilterConverter|convertField|SnapshotFieldConverter|EventStreamFieldConverter" wow-query wow-mongo wow-elasticsearch test example documentation
```

Expected: no production or test references to deleted APIs; only historical migration text may mention removed names when it explicitly describes the migration.

- [ ] **Step 3: Run formatting and module checks**

Run:

```bash
git diff --check
./gradlew :wow-query:check :wow-mongo:check :wow-elasticsearch:check
```

Expected: PASS.

- [ ] **Step 4: Run the complete JVM test suite**

Run:

```bash
./gradlew test --console=plain
```

Expected: BUILD SUCCESSFUL with zero test failures. Existing unrelated compiler/deprecation warnings may remain, but no new warning from the migrated APIs should be introduced.

- [ ] **Step 5: Review the final diff and commit**

Run:

```bash
git status --short
git diff --stat origin/main...HEAD
git diff --check
```

Confirm that only the physical binding migration, compiler renames, tests, and documentation changed. Commit the final documentation and cleanup changes:

```bash
git add documentation/docs/en/guide/query/query-model-schema.md \
  documentation/docs/en/guide/query/query-backend.md \
  documentation/docs/zh/guide/query/query-model-schema.md \
  documentation/docs/zh/guide/query/query-model-schema-phase-zero-design.md \
  documentation/docs/zh/guide/query/query-backend.md \
  documentation/docs/zh/guide/query/query-model-schema-physical-binding-design.md \
  documentation/plans/2026-09-03-query-model-schema-physical-binding-cleanup.md
git commit -m "docs(query): document physical binding ownership"
```

## Completion Criteria

- `FieldConverter`, generic `ProjectionConverter`, generic `SortConverter`, and their abstract wrappers are absent from production code.
- Backend-native `*Compiler` classes are the only Projection/Sort/Filter/Aggregation compilation entry points.
- All Mongo physical field paths come from `QueryModelSchema` bindings or the documented accepted-`COMPATIBLE` identity fallback.
- Snapshot/EventStream identity, nested Element scope, projection, sort, cursor, aggregation, masking, and wire contracts retain their existing behavior.
- `:wow-query:check`, `:wow-mongo:check`, `:wow-elasticsearch:check`, and `./gradlew test` pass.
