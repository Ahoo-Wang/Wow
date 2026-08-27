# EventStream Aggregation and Query Schema Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `EventStreamQueryService` 增加完整 `AggregationQuery` 能力，并让所有 EventStream 查询通过可扩展、跨 MongoDB/Elasticsearch 的 `EVENT_STREAM` Query Schema。

**Architecture:** 在现有 Query Schema 核心增加 `EVENT_STREAM` model 与固定 wire-shape 声明；现有 MongoDB/Elasticsearch Adapter 通过保留旧构造签名的内部参数支持不同 model。EventStream 服务复用 Snapshot 的 resolver、聚合 compiler/pager 和默认 `COMPATIBLE` 行为，不新增 HTTP 路由或 payload 自动推断。

**Tech Stack:** Kotlin 2.4.10、JVM 17、Reactor、JUnit Jupiter、FluentAssert、MongoDB Reactive Streams、Spring Data Elasticsearch。

**Spec:** `docs/superpowers/specs/2026-08-27-event-stream-aggregation-schema-design.md`

## Global Constraints

- 默认使用中文文档与说明。
- 不新增依赖、Gradle 模块、配置项、HTTP/OpenAPI/Schema HTTP 端点、CI/CD 或发布逻辑。
- `body.body.*` 不从 aggregate state 或事件类型自动推断；只允许现有显式 `QuerySchemaSource` 声明。
- 默认 `QuerySchemaValidationMode.COMPATIBLE` 保留未知 payload 字段透传；`STRICT` 保持 fail-closed。
- 保留现有公开构造函数 JVM 签名和第三方 `EventStreamQueryService`/`EventStreamQueryHandler` 实现兼容性。
- 不把 `aggregate` 提升到通用 `QueryService`/`QueryHandler`。
- 每个非平凡行为先写失败测试，使用 `me.ahoo.test.asserts.assert`，完成前运行双后端真实 EventStream TCK。

---

### Task 1: 定义 EVENT_STREAM Schema 模型与系统字段

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaTypes.kt`
- Modify: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaTypesTest.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/SystemQuerySchemaSource.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/SystemQuerySchemaSourceTest.kt`
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSource.kt`
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSourceTest.kt`

**Interfaces:**
- Consumes: `MessageRecords`、`DomainEventRecords`、现有 `QuerySchemaDeclaration`。
- Produces: `QueryModel.EVENT_STREAM`；`SystemQuerySchemaSource.declaration(QueryModel.EVENT_STREAM)`；Snapshot-only 默认 `JsonQuerySchemaSource`。

- [ ] **Step 1: 写 QueryModel 与系统字段失败测试**

在 `QuerySchemaTypesTest` 增加：

```kotlin
@Test
fun `should provide event stream query model`() {
    QueryModel("EVENT_STREAM").assert().isEqualTo(QueryModel.EVENT_STREAM)
}
```

在 `SystemQuerySchemaSourceTest` 增加一个测试，断言：

```kotlin
val fields = SystemQuerySchemaSource.declaration(QueryModel.EVENT_STREAM).fields
fields.keys.map(LogicalField::value).assert().contains(
    "id", "contextName", "aggregateName", "name", "header",
    "aggregateId", "tenantId", "ownerId", "spaceId", "commandId",
    "requestId", "version", "createTime", "body", "body.id",
    "body.name", "body.revision", "body.bodyType", "body.body",
)
fields.getValue(LogicalField("body")).cardinality.assert()
    .isEqualTo(DeclarationValue.Set(QueryCardinality.MANY))
fields.getValue(LogicalField("body.body")).dynamicChildren.assert()
    .isEqualTo(DeclarationValue.Set(false))
fields.getValue(LogicalField("createTime")).semanticType.assert()
    .isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)))
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.schema.QuerySchemaTypesTest" \
  :wow-query:test --tests "me.ahoo.wow.query.schema.SystemQuerySchemaSourceTest"
```

Expected: FAIL，`EVENT_STREAM` 或对应 system declaration 不存在。

- [ ] **Step 3: 实现最小公共模型与系统声明**

在 `QueryModel` companion 增加：

```kotlin
val EVENT_STREAM = QueryModel("EVENT_STREAM")
```

将 `SystemQuerySchemaSource.declaration` 改为穷尽式 model 分派，并新增不可变 `EVENT_STREAM_DECLARATION`。复用现有 `stringField`、`integerField`、`objectField`、`epochField`，只给 `body` 设置 `QueryCardinality.MANY`，只给 `header` 设置 `dynamicChildren = true`，给 `body.body` 明确设置 `dynamicChildren = false`。给 `field` helper 增加带默认值的 `cardinality` 参数，不新增第二套 builder。

- [ ] **Step 4: 写 JsonQuerySchemaSource model 隔离失败测试**

在 `JsonQuerySchemaSourceTest` 使用计数 resolver：

```kotlin
var resolutions = 0
val source = JsonQuerySchemaSource(
    stateTypeResolver = { resolutions++; StructuralState::class.java },
)
val context = QuerySchemaContext(MOCK_AGGREGATE_METADATA, QueryModel.EVENT_STREAM)

source.load(context).collectList().block().assert().isEmpty()
resolutions.assert().isZero()
```

- [ ] **Step 5: 运行测试确认失败并实现 Snapshot-only 推断**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.query.JsonQuerySchemaSourceTest"
```

Expected: FAIL，resolver 被调用并产生状态声明。

在 `load` 的 `Flux.defer` 开头增加：

```kotlin
if (context.model != QueryModel.SNAPSHOT) {
    return@defer Flux.empty()
}
```

- [ ] **Step 6: 运行三个模块测试并提交**

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.schema.QuerySchemaTypesTest" \
  :wow-query:test --tests "me.ahoo.wow.query.schema.SystemQuerySchemaSourceTest" \
  :wow-schema:test --tests "me.ahoo.wow.schema.query.JsonQuerySchemaSourceTest"
git add wow-api wow-query wow-schema
git commit -m "feat(query): add event stream schema model"
```

Expected: PASS。

---

### Task 2: 让后端 Schema Adapter 支持不同 QueryModel

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapter.kt`
- Modify: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapterTest.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaAdapter.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaAdapterTest.kt`

**Interfaces:**
- Consumes: `QueryModel.EVENT_STREAM`、`EventStreamFieldConverter`。
- Produces: 保留旧公开构造签名的 model-aware Adapter 内部构造函数。

- [ ] **Step 1: 写 MongoDB Adapter 失败测试**

增加测试，通过内部构造函数创建 EventStream adapter，并断言 `id` binding：

```kotlin
val id = LogicalField("id")
val schema = MongoQuerySchemaAdapter.bind(
    logicalSchema = LogicalQuerySchema(mapOf(id to field(QueryValueType.STRING))),
    indexes = emptyList(),
    validatorSchema = null,
    model = QueryModel.EVENT_STREAM,
    fieldConverter = EventStreamFieldConverter,
)

schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM)
schema.fields.getValue(id)
    .bindings.getValue(QueryCapability.EXACT_MATCH)
    .physicalPath.assert().isEqualTo("_id")
```

- [ ] **Step 2: 运行 MongoDB 测试确认失败并实现**

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapterTest"`

Expected: FAIL，内部构造函数不存在或 model 仍为 Snapshot。

保留现有公开二参数 JVM 构造函数：

```kotlin
class MongoQuerySchemaAdapter internal constructor(
    private val collection: MongoCollection<Document>,
    private val database: MongoDatabase?,
    private val model: QueryModel,
    private val fieldConverter: FieldConverter,
) : QuerySchemaBackendAdapter {
    @JvmOverloads
    constructor(
        collection: MongoCollection<Document>,
        database: MongoDatabase? = null,
    ) : this(collection, database, QueryModel.SNAPSHOT, SnapshotFieldConverter)

}
```

让 invalid-container 检查、physical path 和 `QueryModelSchema.model` 使用实例参数；为 companion `bind` 增加 `model`、`fieldConverter` 参数的 overload，现有三参数 overload 委托 Snapshot 默认值。

- [ ] **Step 3: 写 Elasticsearch Adapter 失败测试**

```kotlin
val body = LogicalField("body")
val name = LogicalField("body.name")
val logical = LogicalQuerySchema(
    linkedMapOf(
        body to field(QueryValueType.OBJECT, QueryCardinality.MANY),
        name to field(QueryValueType.STRING),
    ),
)
val mapping = ElasticsearchIndexMapping.from(
    INDEX,
    TypeMapping.of { type ->
        type.properties("body") { property ->
            property.nested { nested ->
                nested.properties("name") { it.keyword { keyword -> keyword } }
            }
        }
    },
)
val schema = ElasticsearchQuerySchemaAdapter.bind(
    logical,
    mapping,
    QueryModel.EVENT_STREAM,
)
schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM)
schema.fields.getValue(body)
    .bindings.assert().containsKey(QueryCapability.ELEMENT_SCOPE)
```

- [ ] **Step 4: 运行 Elasticsearch 测试确认失败并实现**

Run: `./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterTest"`

Expected: FAIL，model 参数不存在或结果仍为 Snapshot。

保留公开二参数构造函数，增加内部三参数构造函数；保留现有二参数 `bind` 并委托新三参数 overload：

```kotlin
internal fun bind(
    logicalSchema: LogicalQuerySchema,
    mapping: ElasticsearchIndexMapping,
): QueryModelSchema = bind(logicalSchema, mapping, QueryModel.SNAPSHOT)
```

- [ ] **Step 5: 运行 Adapter 测试并提交**

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapterTest" \
  :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterTest"
git add wow-mongo wow-elasticsearch
git commit -m "refactor(query): parameterize backend schema models"
```

Expected: PASS，Snapshot 现有测试仍通过。

---

### Task 3: 开放 EventStream 聚合查询入口

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/EventStreamQueryService.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/QueryDsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/filter/EventStreamQueryHandler.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/filter/EventStreamQueryFilter.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/filter/DefaultEventStreamQueryHandlerTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/NoOpSnapshotQueryServiceFactoryTest.kt`
- Modify: `wow-spring/src/main/kotlin/me/ahoo/wow/spring/query/QueryServiceProxy.kt`
- Modify: `wow-spring/src/test/kotlin/me/ahoo/wow/spring/query/QueryServiceProxyTest.kt`

**Interfaces:**
- Consumes: `AggregationQuery`、`QueryType.AGGREGATION`、`QueryModelSchemaProvider`。
- Produces: `EventStreamQueryService.aggregate`、`EventStreamQueryHandler.aggregate`、`AggregationQuery.query(EventStreamQueryService)`、EventStream provider requirement extension。

- [ ] **Step 1: 把 Tail Filter 拒绝测试改为转发失败测试**

用记录型 service factory 替换当前 `event stream tail should reject aggregation queries`：

```kotlin
val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
val service = object : EventStreamQueryService by NoOpEventStreamQueryService(MOCK_AGGREGATE_METADATA) {
    override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> = Flux.just(mapOf("count" to 0L))
}
val context = DefaultQueryContext<AggregationQuery, Flux<DynamicDocument>>(
    QueryType.AGGREGATION,
    MOCK_AGGREGATE_METADATA,
).setQuery(query)

TailEventStreamQueryFilter(EventStreamQueryServiceFactory { service })
    .filter(context, FilterChain { Mono.empty() }).block()
context.getRequiredResult().test().expectNextCount(1).verifyComplete()
```

- [ ] **Step 2: 写 handler、DSL、proxy 失败断言并运行**

在 handler 测试调用 `queryHandler.aggregate(...)`；在 proxy 测试调用 `proxy.aggregate(...)` 并断言 handler 记录 `QueryType.AGGREGATION`；在 event Query DSL 测试或现有 factory 测试中调用 `query.query(service)`。

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.event.filter.DefaultEventStreamQueryHandlerTest" \
  :wow-spring:test --tests "me.ahoo.wow.spring.query.QueryServiceProxyTest"
```

Expected: compilation FAIL，聚合方法不存在。

- [ ] **Step 3: 实现最小接口与路由**

给 `EventStreamQueryService` 和 `EventStreamQueryHandler` 增加返回 `UnsupportedOperationException` 的默认 `aggregate`。`DefaultEventStreamQueryHandler.aggregate` 调用：

```kotlin
flux(namedAggregate, QueryType.AGGREGATION, query)
```

Tail Filter 的 AGGREGATION 分支改为：

```kotlin
context.asAggregationQuery().setResult(queryService::aggregate)
```

Spring proxy 显式调用 `handler.aggregate(namedAggregate, query)`；EventStream Query DSL 直接调用 `queryService.aggregate(this)`。增加与 Snapshot 同形的 `requiredQueryModelSchemaProvider()`。

- [ ] **Step 4: 运行测试并提交**

```bash
./gradlew :wow-query:check :wow-spring:check
git add wow-query wow-spring
git commit -m "feat(query): expose event stream aggregation"
```

Expected: PASS。

---

### Task 4: 提取双后端共享聚合执行路径

**Files:**
- Move: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt` → `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryService.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt`
- Modify: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`
- Move: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt` → `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt`
- Move: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPager.kt` → `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryService.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryService.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPagerTest.kt`

**Interfaces:**
- Consumes: `ResolvedAggregationQuery`、各服务已有 collection/client/converter/batch/keepAlive。
- Produces: `AbstractMongoQueryService.executeAggregation(ResolvedAggregationQuery)`；`AbstractElasticsearchQueryService.executeAggregation(ResolvedAggregationQuery)`。

- [ ] **Step 1: 移动 compiler/pager package 并确认 Snapshot 测试先失败**

只修改 package 声明和测试/服务 imports，不改算法。

Run: `./gradlew :wow-mongo:test :wow-elasticsearch:test`

Expected: 若 imports 未完整更新则 compilation FAIL；全部更新后现有 compiler/pager 测试 PASS。

- [ ] **Step 2: 在抽象 MongoDB 服务写共享执行入口**

把 Snapshot service 当前 result normalization 与 empty summary 逻辑移动到 `AbstractMongoQueryService` 的 protected 方法：

```kotlin
protected fun executeAggregation(resolved: ResolvedAggregationQuery): Flux<DynamicDocument> {
    val query = resolved.query
    val result = collection.aggregate(
        MongoAggregationCompiler(converter).compile(query, resolved.schema),
    ).toFlux().map { it.toAggregationResult(query) }
    return if (query.groupBy.isEmpty()) result.switchIfEmpty(Flux.just(query.emptySummary())) else result
}
```

保留有限 Double、Decimal128、Count/Any/Numeric 规范化的现有代码原样。Snapshot service 改为 `schemaProvider.resolve(...).flatMapMany(::executeAggregation)`。

- [ ] **Step 3: 在抽象 Elasticsearch 服务写共享执行入口**

```kotlin
protected fun executeAggregation(resolved: ResolvedAggregationQuery): Flux<DynamicDocument> =
    ElasticsearchAggregationPager(
        elasticsearchClient,
        indexName,
        queryBatchSize,
        queryKeepAlive,
    ).execute(ElasticsearchAggregationCompiler(filterConverter).compile(resolved.query, resolved.schema))
```

Snapshot service 只保留 schema resolve + method reference。

- [ ] **Step 4: 运行 Snapshot 回归并提交**

```bash
./gradlew :wow-mongo:check :wow-elasticsearch:check
git add wow-mongo wow-elasticsearch
git commit -m "refactor(query): share aggregation execution paths"
```

Expected: Snapshot compiler、pager、service 测试全部 PASS；没有行为变更。

---

### Task 5: 给 MongoDB 与 Elasticsearch EventStream 服务接入 Schema 和聚合

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryService.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryServiceFactory.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryServiceTest.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryService.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryServiceFactory.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryServiceTest.kt`

**Interfaces:**
- Consumes: Task 1 `EVENT_STREAM`；Task 2 model-aware adapters；Task 4 shared execution methods。
- Produces: 实现 `QueryModelSchemaProvider` 的两种 EventStream 服务；带 sources/mode 的兼容工厂构造函数。

- [ ] **Step 1: 写服务 Schema 失败测试**

在两种 integration test 中增加：

```kotlin
@Test
fun `should provide event stream query schema`() {
    val schema = eventStreamQueryService.requiredQueryModelSchemaProvider().schema().block()!!
    schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM)
    schema.fields.assert().containsKey(LogicalField("body.name"))
    schema.fields.getValue(LogicalField("body"))
        .bindings.assert().containsKey(QueryCapability.ELEMENT_SCOPE)
}
```

- [ ] **Step 2: 运行定向集成测试确认失败**

```bash
./gradlew :wow-mongo:integrationTest \
  --tests "me.ahoo.wow.mongo.query.event.MongoEventStreamQueryServiceTest.should provide event stream query schema" \
  :wow-elasticsearch:integrationTest \
  --tests "me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceTest.should provide event stream query schema"
```

Expected: FAIL，服务不是 provider。

- [ ] **Step 3: 实现 MongoDB service/factory**

按 Snapshot service 的 private-primary/public-compatible/internal-provider 构造模式改造。服务委托 `QueryModelSchemaProvider`，覆盖四个 `resolve` overload，并实现：

```kotlin
override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> =
    schemaProvider.resolve(query, validationMode).flatMapMany(::executeAggregation)
```

Factory 使用：

```kotlin
DefaultQueryModelSchemaProvider(
    QuerySchemaContext(materialized, QueryModel.EVENT_STREAM),
    schemaSources,
    MongoQuerySchemaAdapter(collection, database, QueryModel.EVENT_STREAM, EventStreamFieldConverter),
)
```

公开 `MongoEventStreamQueryServiceFactory(database)` 签名必须保留。

- [ ] **Step 4: 实现 Elasticsearch service/factory**

保留现有 public 3 参数与 5 参数 service 构造签名、factory 1 参数与 3 参数构造签名。Factory 复用单个 `ElasticsearchIndexMappingResolver` 并创建：

```kotlin
DefaultQueryModelSchemaProvider(
    QuerySchemaContext(materialized, QueryModel.EVENT_STREAM),
    schemaSources,
    ElasticsearchQuerySchemaAdapter(indexName, indexMappingResolver, QueryModel.EVENT_STREAM),
)
```

服务覆盖所有 resolve overload，并通过 Task 4 protected 方法执行聚合。

- [ ] **Step 5: 运行 Schema 集成测试与现有 EventStream 回归并提交**

```bash
./gradlew :wow-mongo:integrationTest \
  --tests "me.ahoo.wow.mongo.query.event.MongoEventStreamQueryServiceTest" \
  :wow-elasticsearch:integrationTest \
  --tests "me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceTest"
git add wow-mongo wow-elasticsearch
git commit -m "feat(query): add event stream backend aggregation"
```

Expected: PASS，现有 id/limit/null 查询仍通过默认 compatible 模式。

---

### Task 6: 接入 Spring Boot sources 并用共享 TCK 证明跨后端聚合

**Files:**
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfigurationTest.kt`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryServiceSpec.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/EventStreamQueryService.kt` (KDoc only if needed)

**Interfaces:**
- Consumes: sources/mode-aware factories；`AggregationQuery.query(EventStreamQueryService)`。
- Produces: 自动配置完整 Schema sources；MongoDB/Elasticsearch 共用的真实聚合证明。

- [ ] **Step 1: 写自动配置失败测试**

为 MongoDB 和 Elasticsearch 各增加与 Snapshot 对称的 EventStream source 传递测试。复用测试文件已有 helper：

```kotlin
val expected = IllegalStateException("query schema source was used")
val factory = configuration.mongoEventStreamQueryServiceFactory(
    mongoClient = mongoClient("order-service"),
    dataMongoProperties = null,
    currentBoundedContext = MaterializedNamedBoundedContext("order-service"),
    sources = listOf(failingQuerySchemaSource(expected)),
    queryProperties = QueryProperties(QueryProperties.Schema(QuerySchemaValidationMode.STRICT)),
)
(factory.create(MOCK_AGGREGATE_METADATA) as QueryModelSchemaProvider)
    .schema().test().expectErrorSatisfies { it.assert().isSameAs(expected) }.verify()
```

Elasticsearch 测试使用相同 expected/source/properties，并额外传入已有 mock `ElasticsearchIndexMappingResolver`。这两个调用同时固定 auto-configuration 向 factory 传递 sources 与 validation mode 的参数合同。

- [ ] **Step 2: 运行自动配置测试确认失败并接线**

Run:

```bash
./gradlew :wow-spring-boot-starter:test \
  --tests "me.ahoo.wow.spring.boot.starter.mongo.MongoEventSourcingAutoConfigurationTest" \
  --tests "me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchEventSourcingAutoConfigurationTest"
```

Expected: FAIL，EventStream factory 没有接收 sources/mode。

给两个 EventStream factory bean 注入已有 `List<QuerySchemaSource>` 与 `QueryProperties`；Elasticsearch 同时注入已有 `ElasticsearchIndexMappingResolver`。不增加属性类。

- [ ] **Step 3: 在 EventStream TCK 写聚合失败测试**

复用 `generateEventStream` 的 1 个 `MockAggregateCreated` 与 9 个 `MockAggregateChanged`：

```kotlin
@Test
fun aggregateEventsByName() {
    val tenantId = generateGlobalId()
    eventStore.append(generateEventStream(namedAggregate.aggregateId(tenantId = tenantId))).block()

    aggregation {
        filter { tenantId(tenantId) }
        expand("body")
        terms("name", "eventName")
        count("count")
    }.query(eventStreamQueryService)
        .collectMap({ it["eventName"] as String }, { it["count"] as Long })
        .test()
        .assertNext { counts -> counts.values.sum().assert().isEqualTo(10L) }
        .verifyComplete()
}
```

再增加无分组、无匹配文档摘要测试，断言唯一 row 的 `count == 0L`。

- [ ] **Step 4: 运行双后端定向 TCK 确认失败后修正最小实现**

```bash
./gradlew :wow-mongo:integrationTest \
  --tests "me.ahoo.wow.mongo.query.event.MongoEventStreamQueryServiceTest.aggregateEventsByName" \
  :wow-elasticsearch:integrationTest \
  --tests "me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceTest.aggregateEventsByName"
```

Expected before final wiring: FAIL；完成后 PASS。只修复真实服务/Schema/adapter 缺口，不在 TCK 加后端分支。

- [ ] **Step 5: 运行完整相关检查**

```bash
./gradlew \
  :wow-api:check \
  :wow-query:check \
  :wow-schema:check \
  :wow-spring:check \
  :wow-spring-boot-starter:check \
  :wow-mongo:check \
  :wow-elasticsearch:check

./gradlew \
  :wow-mongo:integrationTest \
  :wow-elasticsearch:integrationTest \
  --stacktrace

./gradlew detekt
git diff --check
```

Expected: 所有命令 `BUILD SUCCESSFUL`，双后端 EventStream TCK 均执行新增聚合与 Schema 测试。

- [ ] **Step 6: 提交最终接线**

```bash
git add wow-spring-boot-starter test wow-query
git commit -m "test(query): verify event stream aggregation across backends"
```

---

## Completion Audit

- [ ] `git status --short` 只显示预期状态，且 `git diff main...HEAD --check` 通过。
- [ ] `rg "Event stream query does not support aggregation"` 不再命中生产 Tail Filter。
- [ ] `rg "model = QueryModel.SNAPSHOT"` 只保留 Snapshot 专属构造，不残留 backend adapter 硬编码。
- [ ] EventStream 所有 resolve overload 与 aggregation 都使用同一 provider/mode。
- [ ] 默认 factory 构造签名仍被现有源码测试调用；第三方默认接口测试覆盖 unsupported fallback。
- [ ] MongoDB 与 Elasticsearch 的新增 TCK 测试均实际执行且通过。
- [ ] 未新增 `event/aggregation` 路由、OpenAPI operation、依赖、配置项或模块。
