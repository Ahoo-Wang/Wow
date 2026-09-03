# Query Backend Binding Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Backend execution and Query Schema lifecycle independent responsibilities, paired explicitly and atomically by `QueryBackendBinding`.

**Architecture:** `SnapshotQueryBackendFactory` and `EventStreamQueryBackendFactory` return one immutable `QueryBackendBinding<B>` per materialized aggregate. The Binding contains an execution-only Backend and an independent `QueryModelSchemaProvider`; routing, Spring Gateway registration, and Schema HTTP routes forward that pair without casts or recomposition.

**Tech Stack:** Kotlin 2.4.10, Reactor, JUnit Jupiter, MockK, Spring Framework, Spring Boot, Gradle 9.

**Spec:** `documentation/docs/zh/guide/query/query-gateway-resolved-query-design.md`

## Global Constraints

- This is an explicitly approved source and binary breaking change; Query JSON and Schema HTTP wire contracts remain unchanged.
- Do not retain old Factory overloads, Backend-to-Provider casts, Provider delegation, constructor compatibility bridges, aliases, or adapters.
- `QueryBackend` only compiles and executes `ResolvedQuery`; `QueryModelSchemaProvider` only loads and refreshes Schema.
- Cache and route the complete Binding so Backend and Provider cannot come from different storage selections.
- Remove the unused Snapshot Factory `<S : Any>` type parameter everywhere.
- Do not add dependencies, move Gradle modules, introduce a separate Provider Factory, or change storage routing selection rules.
- Use test-first changes for each observable contract and run the narrowest affected module before committing.

---

### Task 1: Add the Binding contract and migrate the core factories

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryBackend.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaProvider.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryBackend.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryBackendFactory.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/RoutingSnapshotQueryBackendFactory.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/EventStreamQueryBackend.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/EventStreamQueryBackendFactory.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/RoutingEventStreamQueryBackendFactory.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryBackendFactoryTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/RoutingSnapshotQueryBackendFactoryTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/EventStreamQueryBackendFactoryTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/RoutingEventStreamQueryBackendFactoryTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/DefaultQueryModelSchemaProviderTest.kt`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryBackendSpec.kt`

**Interfaces:**
- Produces: `QueryBackendBinding<out B : QueryBackend>(backend: B, schemaProvider: QueryModelSchemaProvider)`.
- Produces: `SnapshotQueryBackendFactory.create(NamedAggregate): QueryBackendBinding<SnapshotQueryBackend>`.
- Produces: `EventStreamQueryBackendFactory.create(NamedAggregate): QueryBackendBinding<EventStreamQueryBackend>`.
- Produces: `Abstract*QueryBackendFactory.createBinding(NamedAggregate)` as the single protected construction hook.
- Removes: both `requiredQueryModelSchemaProvider()` extensions and Snapshot Factory type parameter `<S : Any>`.

- [ ] **Step 1: Rewrite the Factory tests around the desired Binding contract**

Use a real fixed Provider and assert that the complete Binding, not only the Backend, is cached by materialized aggregate:

```kotlin
private val schemaProvider = object : QueryModelSchemaProvider {
    override fun schema(): Mono<QueryModelSchema> = Mono.just(SCHEMA)
    override fun refresh(): Mono<QueryModelSchema> = schema()
}

override fun createBinding(namedAggregate: NamedAggregate) = QueryBackendBinding(
    backend = StubSnapshotQueryBackend(namedAggregate),
    schemaProvider = schemaProvider,
)

val first = factory.create(ORDER)
factory.create(DecoratedNamedAggregate(ORDER)).assert().isSameAs(first)
first.backend.namedAggregate.assert().isEqualTo(ORDER)
first.schemaProvider.assert().isSameAs(schemaProvider)
```

Apply the same contract to EventStream and routing tests. Routing assertions compare the returned Binding identity from the selected factory.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew :wow-query:test \
  --tests "me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactoryTest" \
  --tests "me.ahoo.wow.query.snapshot.RoutingSnapshotQueryBackendFactoryTest" \
  --tests "me.ahoo.wow.query.event.EventStreamQueryBackendFactoryTest" \
  --tests "me.ahoo.wow.query.event.RoutingEventStreamQueryBackendFactoryTest"
```

Expected: test compilation fails because `QueryBackendBinding` and `createBinding` do not exist and Snapshot `create` still requires a type argument.

- [ ] **Step 3: Add the minimum Binding and Factory implementation**

Add beside `ResolvedQuery`:

```kotlin
data class QueryBackendBinding<out B : QueryBackend>(
    val backend: B,
    val schemaProvider: QueryModelSchemaProvider,
)
```

Change each abstract Factory cache to `ConcurrentHashMap<MaterializedNamedAggregate, QueryBackendBinding<...>>`, make `create` return the cached Binding, and rename the protected hook to `createBinding`. Routing factories return the selected factory's Binding unchanged.

Add one module-internal unavailable Provider for the two NoOp factories:

```kotlin
internal class UnavailableQueryModelSchemaProvider(
    private val message: String,
) : QueryModelSchemaProvider {
    override fun schema(): Mono<QueryModelSchema> =
        Mono.error(QuerySchemaUnavailableException(message))

    override fun refresh(): Mono<QueryModelSchema> = schema()
}
```

Make both NoOp factories extend their abstract caching Factory and return a Binding containing the NoOp Backend and this unavailable Provider.

- [ ] **Step 4: Remove the hidden Provider capability and migrate the TCK fixture**

Delete both `requiredQueryModelSchemaProvider()` functions and their imports. In each TCK spec retain separate properties:

```kotlin
lateinit var queryBackendBinding: QueryBackendBinding<SnapshotQueryBackend>
lateinit var snapshotQueryBackend: SnapshotQueryBackend
lateinit var queryModelSchemaProvider: QueryModelSchemaProvider

queryBackendBinding = snapshotQueryBackendFactory.create(MOCK_AGGREGATE_METADATA)
snapshotQueryBackend = queryBackendBinding.backend
queryModelSchemaProvider = queryBackendBinding.schemaProvider
```

Change TCK execution helpers to accept a Binding, load Schema from `binding.schemaProvider`, and invoke `binding.backend`. Update their call sites mechanically. Delete the provider-cast test from `DefaultQueryModelSchemaProviderTest`; provider behavior remains covered directly by that test class.

- [ ] **Step 5: Run the complete query module tests**

Run:

```bash
./gradlew :wow-query:clean :wow-query:check --stacktrace
```

Expected: PASS. Factory cache tests prove complete Binding identity; TCK helpers prove Schema lookup remains subscription-deferred.

- [ ] **Step 6: Commit**

```bash
git add wow-query test/wow-tck
git commit -m "refactor(query): bind backend and schema provider explicitly"
```

---

### Task 2: Make MongoDB Backends execution-only

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackendFactory.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackendFactory.kt`
- Modify: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapterTest.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackendTest.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackendTest.kt`

**Interfaces:**
- Consumes: `QueryBackendBinding` and `Abstract*QueryBackendFactory.createBinding` from Task 1.
- Produces: Mongo Factory Bindings whose Backend and Provider share the same collection, database, model, converter, and schema sources.
- Removes: `schemaProvider` Backend constructor parameters, Provider delegation, and Backend-local default Provider builders.

- [ ] **Step 1: Migrate Mongo tests to address Binding members explicitly**

Replace Factory result casts and provider lookups with:

```kotlin
val binding = factory.create(MOCK_AGGREGATE_METADATA)
val backend = binding.backend as MongoSnapshotQueryBackend
val schema = binding.schemaProvider.schema().block()!!
```

Change local `resolved` and aggregation helpers to accept the corresponding Binding so they never recover Provider from Backend. Preserve direct Backend construction tests for custom filter converters; those tests pass an already resolved query and must not expect a Provider.

- [ ] **Step 2: Run the focused Mongo tests and verify RED**

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapterTest"
```

Expected: production compilation fails because Mongo factories still override `createBackend` and return a Backend instead of the Binding required by Task 1.

- [ ] **Step 3: Move Provider ownership into Mongo factories**

Each Factory constructs the Provider and Backend from the same resolved collection, then returns them together:

```kotlin
override fun createBinding(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> {
    val materialized = namedAggregate.materialize()
    val collection = database.getCollection(namedAggregate.toSnapshotCollectionName())
    val provider = DefaultQueryModelSchemaProvider(
        context = QuerySchemaContext(materialized, QueryModel.SNAPSHOT),
        sources = schemaSources,
        adapter = MongoQuerySchemaAdapter(collection, database),
    )
    return QueryBackendBinding(
        MongoSnapshotQueryBackend(materialized, collection),
        provider,
    )
}
```

Apply the EventStream equivalent with `QueryModel.EVENT_STREAM` and `EventStreamFieldConverter`. Remove every Provider import, delegated interface, constructor property, and `defaultSchemaProvider` companion function from both Backends.

- [ ] **Step 4: Run Mongo unit and integration tests**

Run:

```bash
./gradlew :wow-mongo:clean :wow-mongo:test :wow-mongo:integrationTest --stacktrace
```

Expected: PASS, including schema refresh, custom converter ownership, projection, cursor, aggregation, and TCK behavior.

- [ ] **Step 5: Commit**

```bash
git add wow-mongo
git commit -m "refactor(mongo): separate query backend schema provider"
```

---

### Task 3: Make Elasticsearch Backends execution-only

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackendFactory.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryBackendFactory.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackendTest.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotMappingQueryTest.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackendTest.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryBackendTest.kt`

**Interfaces:**
- Consumes: Task 1 Binding contract.
- Produces: Elasticsearch Factory Bindings whose Backend and Provider share the same client, index name, mapping resolver, model, and schema sources.
- Removes: `schemaProvider` Backend constructor parameters, Provider delegation, and Backend-local default Provider builders.

- [ ] **Step 1: Migrate Elasticsearch tests to Binding**

Use `factory.create(...).backend` for execution and `.schemaProvider` for mapping/schema assertions. Change helper functions from `resolved(backend, query)` to `resolved(binding, query)` and load Schema only from the Binding.

- [ ] **Step 2: Run a focused test and verify RED**

Run:

```bash
./gradlew :wow-elasticsearch:test \
  --tests "me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryBackendTest" \
  --tests "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotMappingQueryTest"
```

Expected: production compilation fails because Elasticsearch factories have not adopted the Task 1 return contract.

- [ ] **Step 3: Return Binding from both Elasticsearch factories**

Construct `DefaultQueryModelSchemaProvider` in each Factory with the same `ElasticsearchIndexMappingResolver` and index selected for its Backend. Return `QueryBackendBinding(backend, provider)`. Delete Provider state, delegation, and default Provider companion functions from both concrete Backends.

- [ ] **Step 4: Run Elasticsearch unit and integration tests**

Run:

```bash
./gradlew :wow-elasticsearch:clean :wow-elasticsearch:test :wow-elasticsearch:integrationTest --stacktrace
```

Expected: PASS, including mapping refresh, aliases, runtime fields, cursor, aggregation, and TCK behavior.

- [ ] **Step 5: Commit**

```bash
git add wow-elasticsearch
git commit -m "refactor(elasticsearch): separate query backend schema provider"
```

---

### Task 4: Assemble Spring Gateways from Binding

**Files:**
- Modify: `wow-spring/src/main/kotlin/me/ahoo/wow/spring/query/SnapshotQueryGatewayRegistrar.kt`
- Modify: `wow-spring/src/main/kotlin/me/ahoo/wow/spring/query/EventStreamQueryGatewayRegistrar.kt`
- Modify: `wow-spring/src/test/kotlin/me/ahoo/wow/spring/query/QueryGatewayRegistrarTest.kt`

**Interfaces:**
- Consumes: routed Factory Binding from Task 1.
- Produces: Gateways configured with `binding.backend` and `binding.schemaProvider` without runtime type checks.

- [ ] **Step 1: Separate Backend and Provider in the registrar test fixtures**

Make `SnapshotBackend` and `EventBackend` implement only their Backend interfaces. Create independent recording Providers and return a Binding from each fake Factory:

```kotlin
val snapshotBinding = QueryBackendBinding(snapshotBackend, snapshotProvider)

override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> {
    snapshotFactoryCalls.incrementAndGet()
    return snapshotBinding
}
```

Keep assertions that the Provider is called once and that the identical Schema instance reaches the Backend. The test must prove a Backend with no Provider capability registers and executes successfully.

- [ ] **Step 2: Run the registrar test and verify RED**

Run:

```bash
./gradlew :wow-spring:test --tests "me.ahoo.wow.spring.query.QueryGatewayRegistrarTest"
```

Expected: FAIL because Registrars still call `requiredQueryModelSchemaProvider()` on `binding.backend`.

- [ ] **Step 3: Destructure Binding in both Registrars**

Use one Factory call per aggregate:

```kotlin
val binding = appContext.getBean(SnapshotQueryBackendFactory::class.java).create(namedAggregate)
DefaultSnapshotQueryGateway<Any>(
    namedAggregate = namedAggregate,
    backend = binding.backend,
    schemaProvider = binding.schemaProvider,
    // existing mode, target type, filters, and error handler remain unchanged
)
```

Apply the EventStream equivalent. Do not add null handling or fallback selection.

- [ ] **Step 4: Run Spring checks**

Run:

```bash
./gradlew :wow-spring:clean :wow-spring:check --stacktrace
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wow-spring
git commit -m "refactor(spring): assemble query gateways from binding"
```

---

### Task 5: Read Schema HTTP providers from Binding

**Files:**
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotSchemaHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/EventStreamSchemaHandlerFunction.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotSchemaHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/event/EventStreamSchemaHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteTestFixtures.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilterTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/QueryBodyExtractorTest.kt`

**Interfaces:**
- Consumes: Factory Binding.
- Produces: Schema and refresh handlers that use only `binding.schemaProvider` while ordinary query fixtures use `binding.backend`.

- [ ] **Step 1: Rewrite Schema handler tests with independent Providers**

Replace `RecordingSchemaBackend : Backend, QueryModelSchemaProvider` with a plain NoOp Backend plus a separate `RecordingSchemaProvider`. Fake factories return `QueryBackendBinding(backend, provider)`. Rename the unavailable case to state that an unavailable Provider returns HTTP 503; do not describe Backend capability inference.

- [ ] **Step 2: Run both handler tests and verify RED**

Run:

```bash
./gradlew :wow-webflux:test \
  --tests "me.ahoo.wow.webflux.route.snapshot.SnapshotSchemaHandlerFunctionTest" \
  --tests "me.ahoo.wow.webflux.route.event.EventStreamSchemaHandlerFunctionTest"
```

Expected: FAIL because handlers still cast the Factory result Backend to Provider.

- [ ] **Step 3: Read Provider directly from Binding**

For read and refresh routes use:

```kotlin
provider = {
    snapshotQueryBackendFactory.create(aggregateMetadata(metadata)).schemaProvider
}
```

Apply the EventStream equivalent. Update shared route/query fixtures to use `.backend` when they need raw execution.

- [ ] **Step 4: Run WebFlux checks**

Run:

```bash
./gradlew :wow-webflux:clean :wow-webflux:check --stacktrace
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wow-webflux
git commit -m "refactor(webflux): resolve query schema from binding"
```

---

### Task 6: Split unavailable Starter components and migrate routing tests

**Files:**
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/UnavailableQueryBackend.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/routing/StorageRoutingAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/routing/StorageRouteResolverTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`

**Interfaces:**
- Consumes: Binding-returning core/storage factories.
- Produces: cached unavailable Bindings with separate erroring Backend and Provider objects.

- [ ] **Step 1: Update Starter tests to the explicit pair**

Fake factories return Bindings. Raw Backend checks call `factory.create(namedAggregate).backend`; schema checks call `.schemaProvider`. Keep the existing observable contract: when no storage backend is configured, managed Snapshot and EventStream Gateway calls fail with `QuerySchemaUnavailableException` before filters run.

- [ ] **Step 2: Run Query auto-configuration tests and verify RED**

Run:

```bash
./gradlew :wow-spring-boot-starter:test \
  --tests "me.ahoo.wow.spring.boot.starter.query.QueryAutoConfigurationTest"
```

Expected: production compilation fails because unavailable factories still return Backends that implement Provider.

- [ ] **Step 3: Split unavailable execution and schema lifecycle**

Make `UnavailableQueryBackend` implement only `QueryBackend`. Add a private `UnavailableQueryModelSchemaProvider` that emits the same `QuerySchemaUnavailableException` message. Make both unavailable factories extend the matching abstract caching Factory and return:

```kotlin
QueryBackendBinding(
    backend = UnavailableSnapshotQueryBackend(materialized),
    schemaProvider = UnavailableQueryModelSchemaProvider(materialized),
)
```

Do not change Bean names, conditional annotations, storage route keys, or error messages.

- [ ] **Step 4: Run Starter checks**

Run:

```bash
./gradlew :wow-spring-boot-starter:clean :wow-spring-boot-starter:check --stacktrace
```

Expected: PASS, including MongoDB/Elasticsearch bindings, storage routing, WebFlux auto-configuration, and unavailable Gateway behavior.

- [ ] **Step 5: Commit**

```bash
git add wow-spring-boot-starter
git commit -m "refactor(starter): bind unavailable query components"
```

---

### Task 7: Migrate benchmarks and remove all executable remnants

**Files:**
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QueryGatewayBackendBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/SchemaMaskGatewayBenchmark.kt`
- Modify: any remaining non-document source returned by the removal scan below.

**Interfaces:**
- Consumes: Factory Binding and independently supplied Provider.
- Produces: benchmark fixtures with no Backend/Provider dual-role test doubles.

- [ ] **Step 1: Update benchmark fixtures**

Store the Factory Binding once, pass `binding.backend` and `binding.schemaProvider` to the Gateway benchmark, and split any delegated Backend/Provider double into two objects.

- [ ] **Step 2: Run the executable-remnant scan**

Run:

```bash
rg -n "requiredQueryModelSchemaProvider|QueryModelSchemaProvider by schemaProvider" \
  wow-query wow-mongo wow-elasticsearch wow-spring wow-webflux wow-spring-boot-starter \
  wow-benchmarks test/wow-tck
rg -n "create<[^>]+>\\(" \
  wow-query wow-mongo wow-elasticsearch wow-spring wow-webflux wow-spring-boot-starter \
  wow-benchmarks test/wow-tck
```

Expected before the final mechanical migration: the first command finds remaining removed mechanisms and the second finds Snapshot Factory type arguments plus unrelated generic `create` calls. Replace every remaining Snapshot Factory `create<T>` with `create`, every raw Factory consumer with `.backend`, and every Schema consumer with `.schemaProvider`. After migration the first command has no matches; manually verify every second-command match is unrelated to `SnapshotQueryBackendFactory`.

- [ ] **Step 3: Compile benchmark artifacts**

Run:

```bash
./gradlew :wow-benchmarks:test :wow-benchmarks:jmhJar --stacktrace
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add wow-benchmarks wow-query wow-mongo wow-elasticsearch wow-spring wow-webflux \
  wow-spring-boot-starter test/wow-tck
git commit -m "test(query): migrate backend binding consumers"
```

---

### Task 8: Publish the final contract in maintained docs and skills

**Files:**
- Modify: `documentation/docs/en/guide/data-access.md`
- Modify: `documentation/docs/zh/guide/data-access.md`
- Modify: `documentation/docs/en/guide/query/masking.md`
- Modify: `documentation/docs/zh/guide/query/masking.md`
- Modify: `documentation/docs/en/guide/query/query-backend.md`
- Modify: `documentation/docs/zh/guide/query/query-backend.md`
- Modify: `documentation/docs/en/guide/query/query-gateway.md`
- Modify: `documentation/docs/zh/guide/query/query-gateway.md`
- Modify: `documentation/docs/en/guide/query/query-model-schema.md`
- Modify: `documentation/docs/zh/guide/query/query-model-schema.md`
- Modify: `documentation/docs/en/guide/query/v9-query-migration.md`
- Modify: `documentation/docs/zh/guide/query/v9-query-migration.md`
- Modify: `documentation/docs/zh/guide/query/query-gateway-resolved-query-design.md`
- Modify: `skills/wow-develop/references/query-read-model.md`
- Modify: `skills/wow-migrate/references/migration-risk-map.md`
- Modify: `skills/wow-migrate/evals/behavior.jsonl`

**Interfaces:**
- Produces: one maintained description of Factory → Binding → Backend/Provider ownership.
- Preserves: `documentation/plans/` and `document/design/` as explicit historical records.

- [ ] **Step 1: Replace transitional ownership language**

Document these exact facts in Chinese and English:

- Factory returns and caches `QueryBackendBinding` by materialized aggregate.
- Registrar obtains Backend and Provider from the same routed Binding.
- Schema HTTP handlers use that Binding's Provider.
- Direct Factory access returns a Binding; `.backend` is the trusted raw execution boundary.
- Custom Backends never implement Provider; custom factories explicitly pair both objects.
- Schema unavailable fails all managed queries closed before Backend subscription.
- The Factory return change and removed Snapshot generic are source/binary breaking; wire contracts are unchanged.

Set the design status to `已实现`. Remove all phrases that call this wiring current, temporary, transitional, or deferred.

- [ ] **Step 2: Update skill decisions and eval expectations**

Teach `wow-develop` and `wow-migrate` to select `factory.create(namedAggregate).backend` only for trusted raw diagnostics, to use `.schemaProvider` for Schema lifecycle, and to construct `QueryBackendBinding` in custom factories. Update the B16 migration expected behavior without adding compatibility advice.

- [ ] **Step 3: Verify docs and skills**

Run:

```bash
cd documentation && pnpm docs:build
cd ..
python3 /Users/ahoo/.codex/skills/.system/skill-creator/scripts/quick_validate.py skills/wow-develop
python3 /Users/ahoo/.codex/skills/.system/skill-creator/scripts/quick_validate.py skills/wow-migrate
while IFS= read -r line; do
  printf '%s\n' "$line" | jq -e 'true' >/dev/null || exit 1
done \
  < skills/wow-migrate/evals/behavior.jsonl
```

Expected: all commands exit 0.

- [ ] **Step 4: Commit**

```bash
git add documentation/docs skills
git commit -m "docs(query): publish backend binding ownership"
```

---

### Task 9: Verify the zero-debt boundary

**Files:**
- Verify only; modify a file only if a failing check identifies an in-scope defect.

**Interfaces:**
- Verifies every acceptance criterion in the approved design.

- [ ] **Step 1: Prove historical executable mechanisms are absent**

Run:

```bash
rg -n "requiredQueryModelSchemaProvider|QueryModelSchemaProvider by schemaProvider" \
  wow-query wow-mongo wow-elasticsearch wow-spring wow-webflux wow-spring-boot-starter \
  wow-benchmarks test/wow-tck
rg -n "create<[^>]+>\\(" \
  wow-query wow-mongo wow-elasticsearch wow-spring wow-webflux wow-spring-boot-starter \
  wow-benchmarks test/wow-tck
rg -n "current Spring|当前 Spring|temporar|暂时|装配过渡|留待|后续阶段" \
  documentation/docs skills
```

Expected: the removed-mechanism and maintained-document scans return no matches. Inspect generic `create` matches and confirm none are Snapshot Query Backend Factory calls. Mentions in `documentation/plans/` and `document/design/` are historical records and excluded.

- [ ] **Step 2: Run all affected module checks from a clean state**

Run:

```bash
./gradlew \
  :wow-query:clean :wow-query:check \
  :wow-mongo:clean :wow-mongo:check \
  :wow-elasticsearch:clean :wow-elasticsearch:check \
  :wow-spring:clean :wow-spring:check \
  :wow-webflux:clean :wow-webflux:check \
  :wow-spring-boot-starter:clean :wow-spring-boot-starter:check \
  :wow-benchmarks:clean :wow-benchmarks:check \
  --stacktrace
```

Expected: BUILD SUCCESSFUL with no failed tests or static-analysis tasks.

- [ ] **Step 3: Run integration and benchmark smoke verification**

Run:

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest :wow-benchmarks:jmhJar --stacktrace
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Inspect the final diff**

Run:

```bash
git diff origin/main...HEAD --check
git status --short
git diff --stat origin/main...HEAD
```

Confirm there are no compatibility wrappers, generated outputs, unrelated edits, unresolved placeholders, or uncommitted files.
