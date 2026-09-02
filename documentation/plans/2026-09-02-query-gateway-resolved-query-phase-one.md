# QueryGateway ResolvedQuery Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 QueryGateway 在每次订阅中固定一个 QueryModelSchema，完成过滤与解析后只把 `ResolvedQuery` 交给 QueryBackend，并移除 Backend 内的 Schema 获取、准入和验证模式职责。

**Architecture:** Gateway 从显式注入的 Provider 获取 Schema，构造带非空 Schema 的 QueryContext，执行 Filter 后调用 `QueryModelSchema.resolve`，再把 query 与同一个 Schema 封装为 ResolvedQuery。MongoDB 与 Elasticsearch Backend 只编译和执行 ResolvedQuery；Projection 与 Aggregation Compiler 直接使用其中的非空 Schema，不再通过 Reactor Context、Provider fallback 或 Backend validationMode 协调。

**Tech Stack:** Kotlin 2.4.10、JVM 17、Reactor、Jackson、JUnit Jupiter、FluentAssert、MongoDB Reactive Streams、Elasticsearch Java Client、Spring Framework、Spring Boot、Gradle、VitePress

**Spec:** `documentation/docs/zh/guide/query/query-gateway-resolved-query-design.md`

## Global Constraints

- `QueryGateway` 的十个公共查询方法、参数和 Reactor 返回形态保持不变。
- `QueryBackend` 的六个方法只接收对应泛型的 `ResolvedQuery`；这是明确的 source/binary breaking change，不保留旧方法、重载或适配器。
- `QueryContext.schema` 构造必填、非空且为 `val`；Schema 不放入 attributes 或 Reactor Context。
- 每次订阅只调用一次 Provider；Filter、Resolver、Backend 与 Mask 使用同一个 Schema 引用。
- Schema 不可用时失败关闭，不构造 QueryContext、不调用 Filter、不订阅 Backend。
- Cursor 唯一排序由 `QueryModelSchema.resolve(ICursorQuery)` 按 QueryModel 补充：Snapshot 使用 `aggregateId`，EventStream 使用 `id`。
- `QuerySchemaValidationMode` 属于 Gateway Prepare；MongoDB、Elasticsearch Backend 与 Factory 不再持有它。
- Projection 仍原样透传公共 Query；Backend Projection Converter 必须接收非空 Schema 后编译本地语法。
- 具体 Backend 可以暂时继续实现 `QueryModelSchemaProvider`，仅供 Factory、Schema HTTP 路由和 Gateway 装配使用；执行方法不得调用 Provider。
- 保留 `QueryModelSchemaProvider.resolve(...)` 的直接调用合同，但移除其 Reactor Context 读取；是否删除该 API 不在本阶段。
- 不增加 PreparedQuery、Planner、Engine、Registry、Schema Service、QueryType 分发抽象或 Backend 计划缓存。
- 保持 Reactor 路径非阻塞；测试使用 FluentAssert `.assert()` 和 Reactor `StepVerifier`。
- 每次只暂存当前任务列出的路径，保留工作区中用户的其他修改。

## Change Map

- `wow-query`: ResolvedQuery、QueryBackend SPI、QueryContext Schema、Gateway Prepare、Cursor 唯一排序、Schema Mask 与旧 Schema 桥清理。
- `wow-mongo`: Abstract Backend 统一解包 ResolvedQuery；Projection/Aggregation 使用非空 Schema；具体 Backend 只保留存储与 Provider 装配。
- `wow-elasticsearch`: 与 MongoDB 相同，并把现有私有原生 `ResolvedQuery` 改名为 `CompiledQuery`。
- `wow-spring`: Registrar 显式把 routed Backend 的 Provider 与 validationMode 交给 Gateway。
- `wow-spring-boot-starter`: 从现有属性发布 validationMode Bean；Backend Factory 配置不再接收 Mode；Unavailable Backend 发布失败的 Schema Provider。
- `test/wow-tck`: 所有 Backend 合同输入先按固定 Schema 解析，再封装为 ResolvedQuery。
- `wow-webflux`、`wow-benchmarks`、MongoDB/Elasticsearch integrationTest: 迁移 QueryContext、Gateway 和 Backend 调用点。
- `documentation`: 更新 Gateway、Backend、Schema 和 V9 迁移文档，并把 Phase 1 设计状态改为已实现。

---

### Task 1: Introduce the ResolvedQuery Boundary Type

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryBackend.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewayApiTest.kt`

**Interfaces:**
- Consumes: `QueryModelSchema` and any public query type `Q : Any`.
- Produces: `data class ResolvedQuery<out Q : Any>(val query: Q, val schema: QueryModelSchema)`.

- [ ] **Step 1: Add a failing identity contract test**

Add this test to `QueryGatewayApiTest` without changing QueryBackend signatures yet:

```kotlin
@Test
fun `resolved query should retain query and schema identities`() {
    val query = SingleQuery(MatchAllFilter)
    val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())

    val resolved = ResolvedQuery(query, schema)

    resolved.query.assert().isSameAs(query)
    resolved.schema.assert().isSameAs(schema)
}
```

- [ ] **Step 2: Run the focused test to prove the type is absent**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.QueryGatewayApiTest.resolved query should retain query and schema identities"
```

Expected: Kotlin test compilation fails because `ResolvedQuery` does not exist.

- [ ] **Step 3: Add the minimal value type beside the Backend contract**

Add above `QueryBackend` in `QueryBackend.kt`:

```kotlin
data class ResolvedQuery<out Q : Any>(
    val query: Q,
    val schema: QueryModelSchema,
)
```

Do not add QueryType, Context, Backend, result mapper, compatibility level, error state or factory methods.

- [ ] **Step 4: Run the focused test**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/QueryBackend.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewayApiTest.kt
git commit -m "refactor(query): add resolved query boundary"
```

---

### Task 2: Move Cursor Uniqueness into QueryModelSchema

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/CursorQueriesTest.kt`

**Interfaces:**
- Consumes: `ICursorQuery.withUniqueSort(QueryField)` and `QueryModelSchema.model`.
- Produces: `QueryModelSchema.resolve(ICursorQuery)` whose returned query always has the model-specific stable unique sort before field resolution.

- [ ] **Step 1: Add failing model-specific Cursor tests**

Add focused tests that assert the resolved value rather than Backend behavior:

```kotlin
@Test
fun `cursor resolution should append the model unique field`() {
    listOf(
        QueryModel.SNAPSHOT to QueryField(MessageRecords.AGGREGATE_ID),
        QueryModel.EVENT_STREAM to QueryField(MessageRecords.ID),
    ).forEach { (model, expected) ->
        val schema = QueryModelSchema(model, emptySet(), emptyMap())
        val resolution = schema.resolve(CursorQuery(MatchAllFilter))

        resolution.value.sort.map(Sort::field).assert().containsExactly(expected)
    }
}

@Test
fun `cursor resolution should not duplicate an existing unique field`() {
    val unique = QueryField(MessageRecords.AGGREGATE_ID)
    val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())
    val query = CursorQuery(MatchAllFilter, sort = listOf(Sort(unique, Sort.Direction.DESC)))

    schema.resolve(query).value.sort.assert().containsExactly(Sort(unique, Sort.Direction.DESC))
}
```

- [ ] **Step 2: Run the focused tests to verify RED**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QueryModelSchemaTest"
```

Expected: the first test observes an empty sort because uniqueness is still added by Backend.

- [ ] **Step 3: Add uniqueness before Resolver admission**

Change only the Cursor overload:

```kotlin
fun resolve(query: ICursorQuery): QuerySchemaResolution<ICursorQuery> = resolver.resolve(
    query.withUniqueSort(
        when (model) {
            QueryModel.SNAPSHOT -> SNAPSHOT_CURSOR_UNIQUE_FIELD
            QueryModel.EVENT_STREAM -> EVENT_STREAM_CURSOR_UNIQUE_FIELD
            else -> throw QuerySchemaValidationException("Cursor query model [$model] is unsupported.")
        },
    ),
)
```

Use two file-level immutable QueryField constants backed by `MessageRecords.AGGREGATE_ID` and `MessageRecords.ID`. Do not introduce a Cursor strategy interface or add a field to public Schema metadata.

- [ ] **Step 4: Run Cursor and Model Schema tests**

```bash
./gradlew :wow-query:test \
  --tests "me.ahoo.wow.query.schema.QueryModelSchemaTest" \
  --tests "me.ahoo.wow.query.CursorQueriesTest"
```

Expected: PASS; existing uniqueness, forbidden-sort and maximum-sort constraints remain unchanged.

- [ ] **Step 5: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaTest.kt
git commit -m "refactor(query): resolve cursor uniqueness in schema"
```

---

### Task 3: Make Gateway the Only Managed Prepare Boundary

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryBackend.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryGateway.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryContext.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryGateway.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/EventStreamQueryGateway.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryBackend.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/EventStreamQueryBackend.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/mask/SchemaMaskQueryFilter.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaProviderResolution.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaSubscription.kt`
- Modify: every `wow-query/src/test` and `wow-query/src/jmh` file returned by `rg -l 'QueryBackend|DefaultQueryContext|DefaultSnapshotQueryGateway|DefaultEventStreamQueryGateway' wow-query/src/test wow-query/src/jmh --glob '*.kt' --glob '*.java'`.
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewayApiTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewaySubscriptionTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/QueryContextTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/DefaultSnapshotQueryGatewayTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/DefaultEventStreamQueryGatewayTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationModeTest.kt`

**Interfaces:**
- Consumes: `ResolvedQuery<Q>`, `QueryModelSchemaProvider`, `QuerySchemaValidationMode`, and the six `QueryModelSchema.resolve` overloads.
- Produces: required `QueryContext.schema`; six `QueryBackend` methods accepting ResolvedQuery; Gateway constructors receiving Provider and Mode explicitly.

- [ ] **Step 1: Add failing API, Context and subscription tests**

Update `QueryGatewayApiTest` to assert all Backend methods have the erased `ResolvedQuery` parameter:

```kotlin
listOf("single", "list", "paged", "cursor", "count", "aggregate").forEach { method ->
    QueryBackend::class.java.getMethod(method, ResolvedQuery::class.java)
        .parameterTypes.assert().containsExactly(ResolvedQuery::class.java)
}
```

Add to `QueryContextTest`:

```kotlin
@Test
fun `schema should be a required first class property`() {
    val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())
    val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
        QueryType.SINGLE,
        MOCK_AGGREGATE_METADATA,
        schema,
    )

    context.schema.assert().isSameAs(schema)
    context.attributes.values.none { it === schema }.assert().isTrue()
}
```

Add two `QueryGatewaySubscriptionTest` cases:

```kotlin
@Test
fun `each subscription should pin one schema through context and backend`() {
    val schemas = ConcurrentLinkedQueue(
        listOf(
            QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap()),
            QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap()),
        ),
    )
    val providerCalls = AtomicInteger()
    val provider = object : QueryModelSchemaProvider {
        override fun schema(): Mono<QueryModelSchema> = Mono.fromSupplier {
            providerCalls.incrementAndGet()
            checkNotNull(schemas.poll())
        }

        override fun refresh(): Mono<QueryModelSchema> = schema()
    }
    val contextSchemas = CopyOnWriteArrayList<QueryModelSchema>()
    val backendSchemas = CopyOnWriteArrayList<QueryModelSchema>()
    val filter = errorFilter { context, next ->
        contextSchemas += context.schema
        next.filter(context)
    }
    val backend = backend(single = { resolved ->
        backendSchemas += resolved.schema
        Mono.fromSupplier(::snapshotNode)
    })
    val publisher = gateway(backend, provider, filters = listOf(filter))
        .dynamicSingle(SingleQuery(MatchAllFilter))

    StepVerifier.create(publisher.repeat(1)).expectNextCount(2).verifyComplete()
    providerCalls.get().assert().isEqualTo(2)
    contextSchemas.zip(backendSchemas).all { (context, backend) -> context === backend }.assert().isTrue()
    contextSchemas.map(System::identityHashCode).toSet().assert().hasSize(2)
}

@Test
fun `schema failure should not create context or subscribe backend`() {
    val unavailable = QuerySchemaUnavailableException("unavailable")
    val filterCalls = AtomicInteger()
    val backendSubscriptions = AtomicInteger()
    val provider = object : QueryModelSchemaProvider {
        override fun schema(): Mono<QueryModelSchema> = Mono.error(unavailable)
        override fun refresh(): Mono<QueryModelSchema> = schema()
    }
    val filter = errorFilter { context, next ->
        filterCalls.incrementAndGet()
        next.filter(context)
    }
    val backend = backend(single = {
        backendSubscriptions.incrementAndGet()
        Mono.fromSupplier(::snapshotNode)
    })
    val gateway = gateway(backend, provider, filters = listOf(filter))

    gateway.dynamicSingle(SingleQuery(MatchAllFilter)).test()
        .expectErrorMatches { it === unavailable }
        .verify()
    filterCalls.get().assert().isZero()
    backendSubscriptions.get().assert().isZero()
}
```

- [ ] **Step 2: Run focused tests to verify RED**

```bash
./gradlew :wow-query:test \
  --tests "me.ahoo.wow.query.QueryGatewayApiTest" \
  --tests "me.ahoo.wow.query.QueryGatewaySubscriptionTest" \
  --tests "me.ahoo.wow.query.filter.QueryContextTest"
```

Expected: compilation fails because Backend still accepts raw Query types and QueryContext has no Schema property.

- [ ] **Step 3: Change the Backend and Context contracts once**

Use exactly these Backend signatures:

```kotlin
interface QueryBackend : NamedAggregateDecorator {
    fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode>
    fun list(query: ResolvedQuery<IListQuery>): Flux<ObjectNode>
    fun paged(query: ResolvedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>>
    fun cursor(query: ResolvedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>>
    fun count(query: ResolvedQuery<FilterExpression>): Mono<Long>
    fun aggregate(query: ResolvedQuery<AggregationQuery>): Flux<ObjectNode>
}
```

Add `val schema: QueryModelSchema` to `QueryContext` and this required constructor property to `DefaultQueryContext` before `attributes`:

```kotlin
override val schema: QueryModelSchema,
override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
```

Update NoOp Backends and all wow-query test doubles to accept ResolvedQuery. Access `.query` only where the test needs to inspect the public query; never synthesize a fallback Schema.

- [ ] **Step 4: Acquire Schema before constructing Context**

Make `AbstractQueryGateway` receive these explicit properties:

```kotlin
private val backend: QueryBackend,
private val schemaProvider: QueryModelSchemaProvider,
private val validationMode: QuerySchemaValidationMode,
```

Expose the same required parameters from concrete Gateways without defaults or Backend casts:

```kotlin
class DefaultSnapshotQueryGateway<S : Any>(
    namedAggregate: NamedAggregate,
    backend: SnapshotQueryBackend,
    schemaProvider: QueryModelSchemaProvider,
    validationMode: QuerySchemaValidationMode,
    targetType: JavaType,
    filters: List<QueryFilter<QueryContext<*, *>>> = emptyList(),
    errorHandler: ErrorHandler<QueryContext<*, *>> = QueryLogErrorHandler(),
)

class DefaultEventStreamQueryGateway(
    namedAggregate: NamedAggregate,
    backend: EventStreamQueryBackend,
    schemaProvider: QueryModelSchemaProvider,
    validationMode: QuerySchemaValidationMode,
    filters: List<QueryFilter<QueryContext<*, *>>> = emptyList(),
    errorHandler: ErrorHandler<QueryContext<*, *>> = QueryLogErrorHandler(),
)
```

Build the filter chain with `.withSchemaMaskFilter()` and change subscription creation to:

```kotlin
private fun <Q : Any, RESULT : Any, T : Any> mono(
    queryType: QueryType,
    query: Q,
    result: (QueryContext<Q, RESULT>) -> Mono<T>,
): Mono<T> = Mono.defer {
    schemaProvider.schema().flatMap { schema ->
        val context = DefaultQueryContext<Q, RESULT>(queryType, namedAggregate, schema).setQuery(query)
        Mono.defer { chain.filter(context) }
            .then(Mono.defer { result(context) })
            .onErrorResume { original -> observeError(context, original).then(Mono.error(original)) }
    }
}
```

Use the corresponding `Flux.defer { schemaProvider.schema().flatMapMany { schema -> ... } }` form for Flux results. Keep provider errors outside the Context error-handler boundary because no Context exists yet.

- [ ] **Step 5: Resolve the final filtered query exactly once at Backend dispatch**

For each `QueryType` branch, resolve `context.getQuery()` with `context.schema`, require the configured Mode, then pass one wrapper. The SINGLE branch is the template:

```kotlin
when (context.queryType) {
    QueryType.SINGLE -> context.asSingleQuery().run {
        val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
        setResult(backend.single(ResolvedQuery(accepted, schema)))
    }
    QueryType.LIST -> context.asListQuery().run {
        val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
        setResult(backend.list(ResolvedQuery(accepted, schema)))
    }
    QueryType.PAGED -> context.asPagedQuery().run {
        val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
        setResult(backend.paged(ResolvedQuery(accepted, schema)))
    }
    QueryType.CURSOR -> context.asCursorQuery().run {
        val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
        setResult(backend.cursor(ResolvedQuery(accepted, schema)))
    }
    QueryType.COUNT -> context.asCountQuery().run {
        val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
        setResult(backend.count(ResolvedQuery(accepted, schema)))
    }
    QueryType.AGGREGATION -> context.asAggregationQuery().run {
        val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
        setResult(backend.aggregate(ResolvedQuery(accepted, schema)))
    }
}
```

Do not write a generic `Any` dispatcher or store ResolvedQuery in Context attributes. Leave `context.getQuery()` as the final logical query so post-filters retain the pre-existing Context contract.

- [ ] **Step 6: Make Schema Mask consume Context Schema**

Remove Provider and Backend constructor parameters from `SchemaMaskQueryFilter` and `withSchemaMaskFilter`. After `next.filter(context)`, read `val schema = context.schema`, obtain the cached masker by Schema identity, and map results directly. Remove all `withQueryModelSchema(...)` calls.

- [ ] **Step 7: Remove the Reactor Context bridge and old aggregation holder**

- Delete `QueryModelSchemaSubscription.kt`.
- In `QueryModelSchemaProviderResolution.kt`, replace `schemaForQuery()` with `Mono.defer { schema() }`.
- Replace `ResolvedAggregationQuery` with the generic `ResolvedQuery<AggregationQuery>` return for the aggregation Provider extension.
- Delete `ResolvedAggregationQuery` from `QuerySchemaResolver.kt`.
- Keep the existing COMPATIBLE unavailable fallback for direct Provider resolve overloads; Gateway must not call those overloads.

- [ ] **Step 8: Run the complete wow-query check**

```bash
./gradlew :wow-query:check
```

Expected: PASS; all ten Gateway APIs remain unchanged, Backend reflection sees only ResolvedQuery, and subscription identity/failure tests pass.

- [ ] **Step 9: Commit**

```bash
git add wow-query/src/main wow-query/src/test wow-query/src/jmh
git commit -m "refactor(query): prepare resolved queries in gateway"
```

---

### Task 4: Make MongoDB Execute Only ResolvedQuery

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/ProjectionConverter.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/AbstractProjectionConverter.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/ProjectionConverterTest.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCollections.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoProjectionConverter.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackendFactory.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackendFactory.kt`
- Modify: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackendTest.kt`
- Modify: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoProjectionConverterTest.kt`

**Interfaces:**
- Consumes: the six ResolvedQuery Backend signatures.
- Produces: one AbstractMongoQueryBackend implementation that unwraps query/schema and all concrete Mongo Backends inherit without executing Schema logic.

- [ ] **Step 1: Convert the Mongo abstract-backend test to the new input**

Create one fixed `QueryModelSchema` in `AbstractMongoQueryBackendTest`, wrap every direct operation, and add an identity assertion through the projection mock:

```kotlin
val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())
backend.single(ResolvedQuery(SingleQuery(MatchAllFilter), schema))
verify(exactly = 1) { backend.projectionConverter.convert(any(), same(schema)) }
```

Replace nullable ProjectionConverter expectations with the same fixed Schema.

- [ ] **Step 2: Run the focused test to verify RED**

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.AbstractMongoQueryBackendTest"
```

Expected: compilation fails until Mongo Backend accepts ResolvedQuery and ProjectionConverter requires Schema.

- [ ] **Step 3: Make Projection compilation require Schema**

Change these signatures from `QueryModelSchema?` to `QueryModelSchema`:

```kotlin
fun convert(projection: Projection, schema: QueryModelSchema): T
```

In `AbstractProjectionConverter`, resolve projection roots with:

```kotlin
QueryField(fieldConverter.convert(schema.field(this)?.projectionField?.path ?: path))
```

Remove the public Mongo `findDocument` overload that calls the Schema-aware overload with null. Keep one function whose Schema parameter is non-null.

- [ ] **Step 4: Centralize all six Mongo operations in the abstract Backend**

Remove the five `resolve(...)` hooks, `cursorUniqueField`, nullable Schema parameters and raw-query overrides. Implement the wrapper methods directly:

```kotlin
override fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode> =
    executeSingle(query.query, query.schema)

override fun list(query: ResolvedQuery<IListQuery>): Flux<ObjectNode> {
    require(query.query.limit >= 0) { "limit must be greater than or equal to 0." }
    return executeList(query.query, query.schema)
}

override fun paged(query: ResolvedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>> =
    executePaged(query.query, query.schema)

override fun cursor(query: ResolvedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>> =
    executeCursor(query.query, query.schema)

override fun count(query: ResolvedQuery<FilterExpression>): Mono<Long> =
    executeCount(query.query)

override fun aggregate(query: ResolvedQuery<AggregationQuery>): Flux<ObjectNode> =
    executeAggregation(query.query, query.schema)
```

Make `executeAggregation(query, schema)` compile with `MongoAggregationCompiler(converter).compile(query, schema)`. Cursor must consume the already-present unique sort and must not call `withUniqueSort`.

- [ ] **Step 5: Delete concrete Mongo Prepare logic**

From both concrete Backends remove validationMode, executeWithQuerySchema, requireAccepted, Provider resolve imports, cursor unique field and all six operation overrides. Retain Schema Provider delegation, collection, converters, object-node normalization and default unavailable Provider for custom converters.

From both Mongo factories remove the validationMode constructor property and stop passing it to concrete Backends. Do not change collection routing, Schema Sources or Adapter construction.

- [ ] **Step 6: Run Mongo unit checks**

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.converter.ProjectionConverterTest"
./gradlew :wow-mongo:check
```

Expected: PASS; no Mongo execution method references Provider, validationMode, requireAccepted or withUniqueSort.

- [ ] **Step 7: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/converter \
  wow-query/src/test/kotlin/me/ahoo/wow/query/converter \
  wow-mongo/src/main wow-mongo/src/test
git commit -m "refactor(mongo): execute resolved queries"
```

---

### Task 5: Make Elasticsearch Execute Only ResolvedQuery

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchProjectionConverter.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackendFactory.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryBackendFactory.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackendTest.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchProjectionConverterTest.kt`

**Interfaces:**
- Consumes: non-null ProjectionConverter Schema and six ResolvedQuery Backend methods.
- Produces: Elasticsearch requests compiled only from wrapper query/schema; native filter/sort compilation represented by private `CompiledQuery`.

- [ ] **Step 1: Convert the Elasticsearch abstract-backend test to ResolvedQuery**

Use one fixed Schema for every operation and assert the generated SourceFilter follows that exact Schema. The existing converter fixture already maps `state` to `document`; replace the no-Schema test with:

```kotlin
@Test
fun `should require schema when converting projection`() {
    val projection = Projection(include = listOf(QueryField("state")))

    projection.toSourceFilter(schema).includes().assert()
        .containsExactly("document", "document.*")
}
```

In `AbstractElasticsearchQueryBackendTest`, call the Backend with `ResolvedQuery(query, schema)` and capture the SearchRequest. Assert its source filter contains the fixture's resolved root and wildcard subtree; this proves the same wrapper Schema reached request compilation.

- [ ] **Step 2: Run the focused test to verify RED**

```bash
./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryBackendTest"
```

Expected: compilation fails because Elasticsearch still accepts raw queries and nullable Schema.

- [ ] **Step 3: Require Schema in Elasticsearch Projection compilation**

Change `ElasticsearchProjectionConverter.convert`, `Projection.toSourceFilter` and `toSourceFields` to accept non-null QueryModelSchema. Delete the no-argument `Projection.toSourceFilter()` path. Preserve the local output rule of `path` plus `path.*`, distinct in request order.

- [ ] **Step 4: Centralize the six operations in AbstractElasticsearchQueryBackend**

Use the same six wrapper implementations as Task 4, delegating to Elasticsearch execute helpers with `query.query` and `query.schema`. Remove resolve hooks, cursorUniqueField, nullable Schema and `withUniqueSort`.

Rename the private native holder:

```kotlin
private data class CompiledQuery(
    val query: Query,
    val sortOptions: List<SortOptions>,
)
```

Update `compile`, `cursorSearchRequest` and `createSearchRequest` to use CompiledQuery so it cannot be confused with the public Gateway-to-Backend ResolvedQuery.

- [ ] **Step 5: Delete concrete Elasticsearch Prepare logic**

From both concrete Backends remove validationMode, Provider execution bridge, requireAccepted, cursor unique field and the six operation overrides. Retain Provider delegation, index identity, client settings and default Provider construction.

From both factories remove validationMode and stop passing it into concrete Backends. Keep mapping resolver, Schema Sources, query batch size and keep-alive unchanged.

- [ ] **Step 6: Run Elasticsearch checks**

```bash
./gradlew :wow-elasticsearch:check
```

Expected: PASS; generated search, cursor and aggregation requests still use the supplied Schema and no execution method reads Provider or validationMode.

- [ ] **Step 7: Commit**

```bash
git add wow-elasticsearch/src/main wow-elasticsearch/src/test
git commit -m "refactor(elasticsearch): execute resolved queries"
```

---

### Task 6: Move Validation Mode into Spring Gateway Assembly

**Files:**
- Modify: `wow-spring/src/main/kotlin/me/ahoo/wow/spring/query/SnapshotQueryGatewayRegistrar.kt`
- Modify: `wow-spring/src/main/kotlin/me/ahoo/wow/spring/query/EventStreamQueryGatewayRegistrar.kt`
- Modify: `wow-spring/src/test/kotlin/me/ahoo/wow/spring/query/QueryGatewayRegistrarTest.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QuerySchemaAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/UnavailableQueryBackend.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/query/QuerySchemaAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryAutoConfigurationTest.kt`
- Modify: MongoDB and Elasticsearch auto-configuration tests returned by `rg -l 'validationMode' wow-spring-boot-starter/src/test --glob '*.kt'`.

**Interfaces:**
- Consumes: routed Backend instances that still implement QueryModelSchemaProvider, existing `wow.query.schema.validation-mode` property.
- Produces: one conditional QuerySchemaValidationMode Bean and Gateway constructors receiving Provider/Mode independently from Backend execution.

- [ ] **Step 1: Add failing configuration assertions**

Extend `QuerySchemaAutoConfigurationTest`:

```kotlin
contextRunner
    .enableWow()
    .withPropertyValues("wow.query.schema.validation-mode=STRICT")
    .withUserConfiguration(QuerySchemaAutoConfiguration::class.java)
    .run { context ->
        context.getBean(QuerySchemaValidationMode::class.java)
            .assert().isEqualTo(QuerySchemaValidationMode.STRICT)
    }
```

In `QueryGatewayRegistrarTest`, register a COMPATIBLE Mode Bean and make SnapshotBackend/Event Backend implement QueryModelSchemaProvider with a fixed Schema. Assert one Gateway call invokes Provider once and Backend receives a ResolvedQuery whose Schema is the same provider object.

Use this recording shape for the assertion:

```kotlin
private class SnapshotBackend(
    override val namedAggregate: NamedAggregate,
) : SnapshotQueryBackend, QueryModelSchemaProvider {
    val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())
    val backendSchema = AtomicReference<QueryModelSchema>()
    val schemaCalls = AtomicInteger()
    override val name: String = "test"

    override fun schema(): Mono<QueryModelSchema> = Mono.fromSupplier {
        schemaCalls.incrementAndGet()
        schema
    }

    override fun refresh(): Mono<QueryModelSchema> = schema()
    override fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode> = Mono.fromSupplier {
        backendSchema.set(query.schema)
        SNAPSHOT_JSON.toJsonNode()
    }
}
```

Implement the other five methods with their existing empty results and ResolvedQuery parameters. After the registered Gateway call, assert `schemaCalls` is one and `backendSchema.get() === schema`.

- [ ] **Step 2: Run Spring tests to verify RED**

```bash
./gradlew :wow-spring:test --tests "me.ahoo.wow.spring.query.QueryGatewayRegistrarTest"
./gradlew :wow-spring-boot-starter:test \
  --tests "me.ahoo.wow.spring.boot.starter.query.QuerySchemaAutoConfigurationTest"
```

Expected: missing validation mode Bean and old Gateway constructor wiring fail.

- [ ] **Step 3: Publish the existing property value as one Bean**

Add to QuerySchemaAutoConfiguration:

```kotlin
@Bean
@ConditionalOnMissingBean(QuerySchemaValidationMode::class)
fun querySchemaValidationMode(properties: QueryProperties): QuerySchemaValidationMode =
    properties.schema.validationMode
```

Order QueryAutoConfiguration after QuerySchemaAutoConfiguration. Do not introduce a wrapper configuration interface.

- [ ] **Step 4: Pass Provider and Mode explicitly in both Registrars**

For each routed Backend:

```kotlin
val schemaProvider = backend.requiredQueryModelSchemaProvider()
val validationMode = appContext.getBeanProvider(QuerySchemaValidationMode::class.java)
    .getIfAvailable { QuerySchemaValidationMode.COMPATIBLE }
```

Pass both to DefaultSnapshotQueryGateway or DefaultEventStreamQueryGateway. Keep the current named-aggregate route selection and bean names unchanged; Gateway internals must not cast Backend to Provider.

- [ ] **Step 5: Preserve delayed failure for an unavailable Backend**

Make `UnavailableQueryBackend` implement QueryModelSchemaProvider and return `Mono.error(QuerySchemaUnavailableException(...))` from schema/refresh. Convert its six operation signatures to ResolvedQuery. Application startup should still succeed; the first managed query fails during Schema acquisition before Backend subscription.

- [ ] **Step 6: Remove validationMode from storage factory wiring**

Delete validationMode arguments when constructing all four MongoDB/Elasticsearch Backend factories. Remove QueryProperties parameters from storage auto-configuration methods when no other property is read. Update constructor assertions in the corresponding auto-configuration tests; keep the public property and its metadata unchanged because Gateway now consumes it.

- [ ] **Step 7: Run Spring and starter checks**

```bash
./gradlew :wow-spring:check :wow-spring-boot-starter:check
```

Expected: PASS; STRICT property resolves to the Mode Bean, Registrars pass the routed Provider, and no storage Backend Factory stores Mode.

- [ ] **Step 8: Commit**

```bash
git add wow-spring/src/main wow-spring/src/test \
  wow-spring-boot-starter/src/main wow-spring-boot-starter/src/test
git commit -m "refactor(query): configure validation at gateway"
```

---

### Task 7: Migrate Backend TCK, Integrations and Remaining Call Sites

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryBackendSpec.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackendTest.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackendTest.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackendTest.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryBackendTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilterTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteTestFixtures.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QueryGatewayBackendBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/SchemaMaskGatewayBenchmark.kt`
- Modify: every remaining Kotlin/Java call site returned by `rg -l 'DefaultQueryContext|DefaultSnapshotQueryGateway|DefaultEventStreamQueryGateway|\.single\(|\.list\(|\.paged\(|\.cursor\(|\.count\(|\.aggregate\(' wow-webflux wow-benchmarks test/wow-tck wow-mongo/src/integrationTest wow-elasticsearch/src/integrationTest --glob '*.kt' --glob '*.java'` that targets QueryContext, QueryGateway construction or QueryBackend directly.

**Interfaces:**
- Consumes: QueryModelSchema.resolve plus ResolvedQuery.
- Produces: Backend contract tests that explicitly separate Prepare from execution and integration tests that retain previous physical results.

- [ ] **Step 1: Add typed Prepare helpers to both TCK specs**

Store one Schema from the Backend Provider during setup and define these six overloads in each spec:

```kotlin
private lateinit var queryModelSchema: QueryModelSchema

private fun ISingleQuery.resolved(
    mode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
): ResolvedQuery<ISingleQuery> = ResolvedQuery(
    queryModelSchema.resolve(this).requireAccepted(mode),
    queryModelSchema,
)

private fun IListQuery.resolved(
    mode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
): ResolvedQuery<IListQuery> = ResolvedQuery(
    queryModelSchema.resolve(this).requireAccepted(mode),
    queryModelSchema,
)

private fun IPagedQuery.resolved(
    mode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
): ResolvedQuery<IPagedQuery> = ResolvedQuery(
    queryModelSchema.resolve(this).requireAccepted(mode),
    queryModelSchema,
)

private fun ICursorQuery.resolved(
    mode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
): ResolvedQuery<ICursorQuery> = ResolvedQuery(
    queryModelSchema.resolve(this).requireAccepted(mode),
    queryModelSchema,
)

private fun FilterExpression.resolved(
    mode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
): ResolvedQuery<FilterExpression> = ResolvedQuery(
    queryModelSchema.resolve(this).requireAccepted(mode),
    queryModelSchema,
)

private fun AggregationQuery.resolved(
    mode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
): ResolvedQuery<AggregationQuery> = ResolvedQuery(
    queryModelSchema.resolve(this).requireAccepted(mode),
    queryModelSchema,
)
```

At the end of `SnapshotQueryBackendSpec.setup`, assign:

```kotlin
queryModelSchema = snapshotQueryBackend.requiredQueryModelSchemaProvider().schema().block()!!
```

At the end of `EventStreamQueryBackendSpec.setup`, assign:

```kotlin
queryModelSchema = eventStreamQueryBackend.requiredQueryModelSchemaProvider().schema().block()!!
```

Direct Backend calls must use `backend.single(query.resolved())`, `backend.list(query.resolved())`, `backend.paged(query.resolved())`, `backend.cursor(query.resolved())`, `backend.count(filter.resolved())` and `backend.aggregate(query.resolved())`. Do not add an `Any`-based generic dispatcher.

- [ ] **Step 2: Run TCK compilation to verify every raw call is found**

```bash
./gradlew :wow-tck:compileKotlin
```

Expected before completing the migration: compilation reports every Backend call still passing a raw query.

- [ ] **Step 3: Migrate integration Prepare without restoring Backend policy**

In each MongoDB/Elasticsearch integration test, add matching typed `resolved(query, mode)` helpers using the Backend's published Schema. Apply these rules:

- physical execution tests pass COMPATIBLE unless the test explicitly proves an EXACT declaration;
- strict rejection tests call `schema.resolve(query).requireAccepted(STRICT)` and assert failure before invoking Backend;
- compatible unknown-field tests call `requireAccepted(COMPATIBLE)` before Backend;
- remove validationMode arguments from Backend and Factory constructors;
- tests for custom-converter Schema unavailability continue testing Provider.schema directly;
- the existing single-generation projection/masking test constructs a Gateway with explicit Provider and STRICT Mode, then asserts one Provider call.

- [ ] **Step 4: Migrate Context, Gateway and benchmark fixtures**

Pass a fixed Schema to every direct DefaultQueryContext. Pass an explicit Provider and Mode to every DefaultSnapshotQueryGateway/DefaultEventStreamQueryGateway. Change benchmark/test Backend doubles to accept ResolvedQuery and keep fresh ObjectNode allocation per subscription.

- [ ] **Step 5: Run TCK and downstream compilation**

```bash
./gradlew :wow-tck:check :wow-webflux:check :wow-benchmarks:compileJmhKotlin
```

Expected: PASS.

- [ ] **Step 6: Run all four focused Backend integration suites**

```bash
./gradlew :wow-mongo:integrationTest \
  --tests "me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryBackendTest" \
  --tests "me.ahoo.wow.mongo.query.event.MongoEventStreamQueryBackendTest"
./gradlew :wow-elasticsearch:integrationTest \
  --tests "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryBackendTest" \
  --tests "me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryBackendTest"
```

Expected: PASS; filter, sort, cursor projection and aggregation physical behavior matches the Phase 0 baseline.

- [ ] **Step 7: Commit**

```bash
git add test/wow-tck wow-webflux/src/test wow-benchmarks/src/jmh \
  wow-mongo/src/integrationTest wow-elasticsearch/src/integrationTest
git commit -m "test(query): migrate resolved backend contracts"
```

---

### Task 8: Remove Superseded Paths, Update Documentation and Run Final Gates

**Files:**
- Modify: `documentation/docs/zh/guide/query/query-gateway-resolved-query-design.md`
- Modify: `documentation/docs/zh/guide/query/query-gateway.md`
- Modify: `documentation/docs/en/guide/query/query-gateway.md`
- Modify: `documentation/docs/zh/guide/query/query-backend.md`
- Modify: `documentation/docs/en/guide/query/query-backend.md`
- Modify: `documentation/docs/zh/guide/query/query-model-schema.md`
- Modify: `documentation/docs/en/guide/query/query-model-schema.md`
- Modify: `documentation/docs/zh/guide/query/v9-query-migration.md`
- Modify: `documentation/docs/en/guide/query/v9-query-migration.md`
- Modify: any additional executable source or query documentation returned by the stale-symbol scans below.

**Interfaces:**
- Consumes: completed Phase 1 runtime and Backend behavior.
- Produces: no Phase 0 execution bridge, no old aggregation holder, no nullable Backend Schema path, current bilingual public documentation and a fully verified branch.

- [ ] **Step 1: Prove superseded execution symbols are gone**

Run:

```bash
rg -n 'executeWithQuerySchema|withQueryModelSchema|schemaForQuery|ResolvedAggregationQuery' \
  --glob '*.kt' --glob '*.java'
rg -n 'QueryModelSchema\?' \
  wow-query/src/main/kotlin/me/ahoo/wow/query/converter \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query
rg -n 'validationMode' \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query
```

Expected: all three commands return no matches. Direct Provider fallback code may still contain `QuerySchemaValidationMode`, but no Reactor Context key or Backend execution bridge.

- [ ] **Step 2: Verify Backend implementations do not acquire or resolve Schema**

```bash
rg -n 'schemaProvider\.(schema|resolve)|requireAccepted|withUniqueSort' \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query
```

Expected: no matches in Backend execution code. Provider construction and delegation remain allowed.

- [ ] **Step 3: Update bilingual operational contracts**

Document these exact facts:

- Gateway obtains one Schema per subscription before Context creation;
- QueryContext exposes non-null Schema from the beginning of the Filter chain;
- QueryModelSchema appends the model-specific Cursor unique sort;
- QueryBackend receives only ResolvedQuery and never decides validation mode;
- Projection and Aggregation are compiled with ResolvedQuery.schema;
- Schema unavailable fails closed for managed Gateway calls;
- custom Backend migration requires six ResolvedQuery signatures;
- no compatibility overloads exist.

Change the Phase 1 design banner to `状态：已实现。` only after all production and integration checks pass.

- [ ] **Step 4: Run fresh module checks**

```bash
./gradlew :wow-query:check :wow-mongo:check :wow-elasticsearch:check \
  :wow-spring:check :wow-spring-boot-starter:check :wow-webflux:check \
  :wow-tck:check :wow-benchmarks:compileJmhKotlin --rerun-tasks
```

Expected: BUILD SUCCESSFUL with tasks executed rather than only retrieved from cache.

- [ ] **Step 5: Run static analysis and documentation build**

```bash
./gradlew detekt
cd documentation
pnpm docs:build
```

Expected: detekt succeeds and VitePress reports `build complete`.

- [ ] **Step 6: Check the final diff and working tree**

```bash
git diff --check
git status --short
git diff --stat origin/main...HEAD
```

Expected: no whitespace errors; status contains only the planned Phase 1 changes before the final documentation commit.

- [ ] **Step 7: Commit documentation**

```bash
git add documentation/docs
git commit -m "docs(query): document resolved query backend boundary"
```

- [ ] **Step 8: Run the completion verification again after the final commit**

```bash
./gradlew :wow-query:check :wow-mongo:check :wow-elasticsearch:check \
  :wow-spring:check :wow-spring-boot-starter:check :wow-webflux:check \
  :wow-tck:check --rerun-tasks
git status --short
```

Expected: BUILD SUCCESSFUL and an empty working tree.

## Final Review Checklist

- QueryGateway public reflection contract is unchanged.
- QueryBackend reflection contract exposes only ResolvedQuery parameters.
- QueryContext requires a non-null immutable Schema.
- Provider is called once per subscription and not called by Backend execution.
- Filter, Resolver, Backend and Mask share one Schema identity.
- Schema acquisition failure cannot reach Filter or Backend.
- Cursor uniqueness is added before Schema validation and never in Backend.
- Snapshot uses aggregateId; EventStream uses stream id.
- Projection and Aggregation compilers receive non-null ResolvedQuery.schema.
- MongoDB and Elasticsearch concrete Backends contain no validation or fallback logic.
- Spring's existing validation-mode property controls Gateway Prepare.
- Provider direct-resolution fallback remains isolated from managed Gateway execution.
- Reactor Context Schema bridge and ResolvedAggregationQuery are deleted.
- No compatibility overload, wrapper or speculative planner was added.
- TCK, four Backend integration suites, fresh module checks, detekt and docs build pass.
