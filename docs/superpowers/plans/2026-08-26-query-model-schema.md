# 查询模型 Schema Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Snapshot 查询建立由全局系统骨架、聚合逻辑声明和 MongoDB/Elasticsearch 物理事实协商得到的 QueryModelSchema，并同时向 Backend Compiler 与 View Engine 提供唯一元数据基准。

**Architecture:** `wow-api` 定义稳定的公共逻辑类型、Temporal 语义注解和 Metadata DTO；`wow-query` 负责声明合并、缓存刷新、兼容性判断与物理 binding 合同；`wow-schema` 负责 Jackson/JSON Schema 推断；MongoDB、Elasticsearch 各自把物理事实解析为最终 Schema。每个具体 SnapshotQueryService 持有一个 Provider，WebFlux 聚合路由通过同一服务实例读取或刷新 Schema。

**Tech Stack:** Kotlin 2.4.10、JVM 17、Reactor、Jackson 3、Victools JSON Schema、MongoDB Reactive Streams、Elasticsearch Java Client、Spring Boot 4.1、Spring WebFlux、OpenAPI 3.1、JUnit Jupiter、MockK、Reactor Test、Testcontainers、VitePress。

**Spec:** `docs/superpowers/specs/2026-08-26-query-model-schema-design.md`

## Global Constraints

- 首期 QueryModel 仅实现 `SNAPSHOT`，后端仅实现 MongoDB 与 Elasticsearch；EventStream、Projection 只通过开放值对象预留。
- System 是按 QueryModel 全局共享的不可变骨架，不进入扩展 Source 优先级；Snapshot 扩展只能补齐 `state` 的 System-Unset 属性和增加 `state.*`。
- 扩展优先级固定为 Working-directory `400` > Bean `300` > Classpath `200` > `@QueryTemporal` > JSON Schema `100`；同级同叶子不同值失败。
- 约定路径固定为 `./config/wow-query-schema/{contextName}/{aggregateName}/{model}.json` 与 `classpath:wow-query-schema/{contextName}/{aggregateName}/{model}.json`；不增加配置项、目录扫描或 YAML。
- `@QueryTemporal` 只增强 JSON wire shape 为 integer 的字段；默认 `TimeUnit.MILLISECONDS`，不增加通用 `@QuerySemantic`、元注解或 Resolver SPI。
- Backend Adapter 在逻辑合并后运行；业务语义与物理事实正交，binding 不进入公共 HTTP Metadata。
- QueryFieldBinding 只含 physicalPath/storageType，不保存 pipeline、script、runtime field 或执行计划；QueryModelSchema 不发布 QueryService operations 列表。
- Schema 每个 QueryService 实例缓存一份；首次加载并发合并，refresh 成功原子替换、失败保留旧值；不使用 TTL/定时轮询，不计算 hash、changed、version 或相等性。
- `COMPATIBLE` 默认接受 EXACT/COMPATIBLE、拒绝 INCOMPATIBLE；`STRICT` 只接受 EXACT；不提供 DISABLED。
- 普通查询仅在 `QuerySchemaUnavailableException + COMPATIBLE` 时回退现有编译路径；冲突和已知不兼容不得降级。
- 自定义异常固定为 `QuerySchemaValidationException`、`QuerySchemaConflictException`、`QuerySchemaUnavailableException`，继承 WowException，HTTP 分别为 400、500、503。
- HTTP 固定为 `GET /{aggregate}/snapshot/schema` 与 `POST /{aggregate}/snapshot/schema/refresh`；refresh 使用独立 route ID，不使用 Actuator。
- Schema validation 位于 Backend SnapshotQueryService/Compiler，发生在现有 Query Filter Chain 完成 ABAC/masking/rewrite 之后；不新增前置 QueryFilter。
- Metadata endpoint 对已经获得该聚合访问权限的调用方返回全部逻辑字段，不增加用户级 metadata filter。
- 删除 `TypeFieldPaths`、`AggregatedFieldPaths`、`x-wow-query-fields`、聚合字段 enum components 和 `wowElasticsearchMapping`，不保留兼容桥。
- 不新增外部依赖、Gradle module、中央 Registry、Scanner、后台轮询、广播或可执行 pipeline/script 声明。
- Kotlin 测试使用 FluentAssert `.assert()`；每个行为变更执行 RED → GREEN；只 stage 当前任务列出的文件。

---

## File Map

### 新建

- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaTypes.kt` — 公共开放值对象、Temporal 语义与兼容等级。
- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaMetadata.kt` — View Engine/HTTP DTO。
- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QueryTemporal.kt` — integer epoch 专用注解。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt` — 最终 Schema、binding、动态后代查找与 Metadata 投影。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaDeclaration.kt` — context、registration、DeclarationValue、Source 合同和 DSL 数据。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaMerger.kt` — System 锁定、优先级与同级冲突合并。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/SystemQuerySchemaSource.kt` — 全局 Snapshot 骨架。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaProvider.kt` — Adapter 合同、每服务缓存和刷新生命周期。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaSources.kt` — Bean、工作目录和 Classpath 来源。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaDsl.kt` — `querySchemaRegistration` 开发者 DSL。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt` — 请求兼容性、物理路径解析和 validation mode。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaExceptions.kt` — 三类 WowException。
- `wow-schema/src/main/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSource.kt` — JSON Schema 遍历和注解增强。
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapter.kt` — Mongo validator/index/约定绑定。
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaAdapter.kt` — mapping/runtime/multi-field 绑定。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryProperties.kt` — `wow.query.schema.validation-mode`。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QuerySchemaAutoConfiguration.kt` — 来源与配置装配。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotSchemaHandlerFunction.kt` — GET/refresh handlers 与 factories。

### 主要修改

- `wow-schema/build.gradle.kts`、`wow-spring-boot-starter/build.gradle.kts` — 只增加现有项目模块依赖。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryService.kt` — Provider 能力检查扩展。
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryService.kt`、`snapshot/MongoSnapshotQueryService*.kt`、`snapshot/MongoAggregationCompiler.kt` — Schema 解析与 temporal 编译。
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryService.kt`、`ElasticsearchIndexMappingResolver.kt`、`snapshot/ElasticsearchSnapshotQueryService*.kt`、`snapshot/ElasticsearchAggregationCompiler.kt` — 以最终 Schema 替代 mapping 旁路。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfiguration.kt`、`elasticsearch/ElasticsearchEventSourcingAutoConfiguration.kt` — 向 backend factory 注入 Sources 与 mode。
- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/BuiltInHttpRoutes.kt`、`contributor/aggregate/snapshot/SnapshotRouteContributor.kt` — Schema 路由合同。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/ErrorHttpStatusMapping.kt`、Starter `QueryRouteModule.kt`/`WebFluxAutoConfiguration.kt` — HTTP handler 与错误映射。
- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/QueryComponent.kt`、`contributor/QueryContractComponentSupport.kt` — 删除静态字段目录。
- `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt` 与 Mock State — 跨后端合同。
- 中英文 query/webflux/elasticsearch 指南与 OpenAPI snapshot。

### 删除

- `wow-schema/src/main/kotlin/me/ahoo/wow/schema/TypeFieldPaths.kt`
- `wow-schema/src/test/kotlin/me/ahoo/wow/schema/AggregatedFieldPathsTest.kt`
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchMappingEndpointAutoConfiguration.kt`
- `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchMappingEndpointAutoConfigurationTest.kt`

---

### Task 1: 公共 Schema 类型、Temporal 语义与 Metadata DTO

**Files:**
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaTypes.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaMetadata.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QueryTemporal.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaTypesTest.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaMetadataTest.kt`

**Interfaces:**
- Produces: `QueryModel`, `QueryCapability`, `QueryValueType`, `QueryCardinality`, `QuerySemanticType`, `Temporal`, `QueryCompatibilityLevel`, `QueryModelSchemaMetadata`, `QueryFieldSchemaMetadata`, `QueryTemporal`.
- Consumes: existing `LogicalField`, Jackson annotations, Swagger annotations, `TimeUnit`, `JsonNode`.

- [ ] **Step 1: Write RED tests for identifiers, semantic JSON and annotation targets**

```kotlin
@Test
fun `schema identifiers must be safe single segments`() {
    QueryModel("SNAPSHOT").assert().isEqualTo(QueryModel.SNAPSHOT)
    assertThrows<IllegalArgumentException> { QueryModel("../snapshot") }
    assertThrows<IllegalArgumentException> { QueryCapability("FULL.TEXT") }
}

@Test
fun `temporal epoch should round trip`() {
    val semantic: QuerySemanticType = Temporal.Epoch(TimeUnit.SECONDS)
    val json = JsonSerializer.writeValueAsString(semantic)
    json.assert().contains("TEMPORAL_EPOCH").contains("SECONDS")
    JsonSerializer.readValue(json, QuerySemanticType::class.java).assert().isEqualTo(semantic)
}

private data class TemporalFixture(
    @QueryTemporal(TimeUnit.MILLISECONDS) val createdAt: Long,
)
```

- [ ] **Step 2: Run RED**

Run: `./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.schema.*" --stacktrace`

Expected: compilation fails because the schema package does not exist.

- [ ] **Step 3: Implement the public value objects and temporal hierarchy**

```kotlin
data class QueryModel(@get:JsonValue val value: String) {
    init { requireQuerySchemaIdentifier(value) }
    companion object {
        val SNAPSHOT = QueryModel("SNAPSHOT")
        @JvmStatic @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(value: String): QueryModel = QueryModel(value)
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(Temporal.Date::class, name = "TEMPORAL_DATE"),
    JsonSubTypes.Type(Temporal.Epoch::class, name = "TEMPORAL_EPOCH"),
    JsonSubTypes.Type(Temporal.Formatted::class, name = "TEMPORAL_FORMATTED"),
)
interface QuerySemanticType

sealed interface Temporal : QuerySemanticType {
    @JsonTypeName("TEMPORAL_DATE")
    data object Date : Temporal
    @JsonTypeName("TEMPORAL_EPOCH")
    data class Epoch(val timeUnit: TimeUnit = TimeUnit.MILLISECONDS) : Temporal
    @JsonTypeName("TEMPORAL_FORMATTED")
    data class Formatted(val pattern: String) : Temporal {
        init {
            require(pattern.isNotBlank())
            DateTimeFormatter.ofPattern(pattern)
        }
    }
}
```

Define QueryCapability constants exactly as the spec: PRESENCE, EXACT_MATCH, LITERAL_MATCH, RANGE, FULL_TEXT_TERMS, FULL_TEXT_PHRASE, SORT, ELEMENT_SCOPE, AGGREGATE_TERMS, AGGREGATE_NUMERIC, AGGREGATE_TEMPORAL. Define QueryValueType constants STRING, INTEGER, DECIMAL, BOOLEAN, OBJECT. Use one private identifier validator shared by all open value objects.

- [ ] **Step 4: Implement Metadata DTOs and the dedicated annotation**

```kotlin
data class QueryModelSchemaMetadata(
    val model: QueryModel,
    val capabilities: Set<QueryCapability>,
    val fields: List<QueryFieldSchemaMetadata>,
)

@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryTemporal(
    val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
)
```

QueryFieldSchemaMetadata must contain field, title, description, enumValues, valueTypes, nullable, required, cardinality, semanticType, dynamicChildren and capabilities; it must not contain physicalPath or storageType.

- [ ] **Step 5: Run GREEN and public API checks**

Run: `./gradlew :wow-api:check --stacktrace`

Expected: all wow-api tests pass and Jackson round trips the three Temporal variants.

- [ ] **Step 6: Commit**

```bash
git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/schema
git commit -m "feat(query): define query schema contracts"
```

---

### Task 2: 声明模型、全局 System 骨架与确定性合并

**Files:**
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaDeclaration.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaMerger.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/SystemQuerySchemaSource.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaDsl.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaExceptions.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaMergerTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/SystemQuerySchemaSourceTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaTest.kt`

**Interfaces:**
- Consumes: Task 1 public types, `NamedAggregate`, `LogicalField`, Reactor.
- Produces: `QuerySchemaContext`, `QuerySchemaDeclaration`, `QuerySchemaRegistration`, `DeclarationValue`, `QuerySchemaSource`, priorities, `PrioritizedQuerySchemaDeclaration`, `LogicalQuerySchema`, `QueryModelSchema`, `QueryFieldSchema`, `QueryFieldBinding`, `QueryStorageType`, `SystemQuerySchemaSource`, `QuerySchemaMerger`, `querySchemaRegistration`, three exceptions.

- [ ] **Step 1: Write RED merger and skeleton tests**

```kotlin
@Test
fun `higher priority should merge by leaf without erasing lower values`() {
    val json = declaration("state.createdAt", valueTypes = setOf(QueryValueType.INTEGER))
    val bean = declaration("state.createdAt", semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS))

    merger.merge(
        system(),
        listOf(
            PrioritizedQuerySchemaDeclaration(100, json),
            PrioritizedQuerySchemaDeclaration(300, bean),
        ),
    )
        .fields.getValue(LogicalField("state.createdAt"))
        .assert {
            valueTypes.isEqualTo(setOf(QueryValueType.INTEGER))
            semanticType.isEqualTo(Temporal.Epoch(TimeUnit.MILLISECONDS))
        }
}

@Test
fun `same priority different leaf values should conflict`() {
    assertThrows<QuerySchemaConflictException> {
        merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(300, title("A")),
                PrioritizedQuerySchemaDeclaration(300, title("B")),
            ),
        )
    }
}
```

Add tests proving all Snapshot instances receive equal System declarations, `state` is SINGLE+OBJECT, system time fields are Temporal.Epoch(MILLISECONDS), `state.*` is allowed, arbitrary top-level fields and attempts to overwrite system leaves fail.

Define these test-local helpers in QuerySchemaMergerTest so the snippets compile:

```kotlin
private fun system(): QuerySchemaDeclaration =
    SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)

private fun declaration(
    field: String,
    valueTypes: Set<QueryValueType>? = null,
    semanticType: QuerySemanticType? = null,
): QuerySchemaDeclaration

private fun title(value: String): QuerySchemaDeclaration =
    QuerySchemaDeclaration(
        mapOf(LogicalField("state.name") to QueryFieldDeclaration(title = Set(value))),
    )
```

`declaration` constructs one QueryFieldDeclaration and uses Unset for every null helper argument; it does not convert null into Set(null).

- [ ] **Step 2: Run RED**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.*" --stacktrace`

Expected: compilation fails on the missing schema model.

- [ ] **Step 3: Implement declaration/source contracts and exceptions**

```kotlin
sealed interface DeclarationValue<out T> {
    data object Unset : DeclarationValue<Nothing>
    data class Set<T>(val value: T) : DeclarationValue<T>
}

interface QuerySchemaSource {
    val priority: Int
    fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration>
    fun refresh(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = load(context)
}

object QuerySchemaSourcePriority {
    const val JSON_SCHEMA = 100
    const val CLASSPATH = 200
    const val BEAN = 300
    const val WORKING_DIRECTORY = 400
}

internal data class PrioritizedQuerySchemaDeclaration(
    val priority: Int,
    val declaration: QuerySchemaDeclaration,
)
```

Implement QuerySchemaException as one sealed file. Use error codes `QuerySchemaValidation`, `QuerySchemaConflict`, `QuerySchemaUnavailable`; preserve cause in all constructors that accept one.

- [ ] **Step 4: Implement final/logical models and pure Metadata projection**

```kotlin
private val QUERY_STORAGE_TYPE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*")

data class QueryStorageType(val value: String) {
    init { require(QUERY_STORAGE_TYPE_PATTERN.matches(value)) }
}

data class QueryFieldBinding(
    val physicalPath: String,
    val storageType: QueryStorageType?,
)

data class QueryModelSchema(
    val model: QueryModel,
    val capabilities: Set<QueryCapability>,
    val fields: Map<LogicalField, QueryFieldSchema>,
)

data class QueryFieldSchema(
    val title: String?,
    val description: String?,
    val enumValues: List<JsonNode>?,
    val valueTypes: Set<QueryValueType>,
    val nullable: Boolean,
    val required: Boolean,
    val cardinality: QueryCardinality,
    val semanticType: QuerySemanticType?,
    val dynamicChildren: Boolean,
    val bindings: Map<QueryCapability, QueryFieldBinding>,
)

fun QueryModelSchema.toMetadata(): QueryModelSchemaMetadata =
    QueryModelSchemaMetadata(
        model = model,
        capabilities = capabilities,
        fields = fields.entries.sortedBy { it.key.value }.map { (field, schema) ->
            QueryFieldSchemaMetadata(
                field = field,
                title = schema.title,
                description = schema.description,
                enumValues = schema.enumValues,
                valueTypes = schema.valueTypes,
                nullable = schema.nullable,
                required = schema.required,
                cardinality = schema.cardinality,
                semanticType = schema.semanticType,
                dynamicChildren = schema.dynamicChildren,
                capabilities = schema.bindings.keys,
            )
        },
    )
```

Define LogicalQuerySchema with the same logical leaves but no capabilities/bindings; QuerySchemaMerger is its only producer and QuerySchemaBackendAdapter is its only consumer.

Implement exact-field lookup first, then nearest dynamic ancestor lookup. Dynamic binding appends the logical relative suffix to the ancestor physicalPath and never invents a capability absent from the ancestor.

- [ ] **Step 5: Implement System skeleton and merger**

System declaration must define the exact serialized MaterializedSnapshot fields from MessageRecords, StateAggregateRecords and SnapshotRecords. Use STRING for identifiers/operators, INTEGER for version and system epochs, BOOLEAN for deleted, OBJECT for tags/state, Temporal.Epoch(MILLISECONDS) for firstEventTime/eventTime/snapshotTime. Mark serialized fields required/non-null; leave aggregate-specific state display/dynamic leaves Unset.

Merger algorithm:

```kotlin
fun merge(
    system: QuerySchemaDeclaration,
    extensions: List<PrioritizedQuerySchemaDeclaration>,
): LogicalQuerySchema
```

First copy and lock every System `Set` leaf. Validate Snapshot extension paths are `state` or start with `state.`. Group extensions by priority, reject different Set values within the same group, then apply groups low-to-high. Materialize remaining Unset values as title/description/enum/semantic null, valueTypes empty, nullable true, required false, cardinality SINGLE and dynamicChildren false.

- [ ] **Step 6: Implement registration DSL**

```kotlin
fun querySchemaRegistration(
    aggregateType: KClass<*>,
    model: QueryModel,
    block: QuerySchemaDeclarationBuilder.() -> Unit,
): QuerySchemaRegistration = QuerySchemaRegistration(
    context = QuerySchemaContext(aggregateType.java.requiredNamedAggregate().materialize(), model),
    declaration = QuerySchemaDeclarationBuilder().apply(block).build(),
)
```

The field DSL sets only explicitly called leaves; `temporalEpoch(unit)` sets only semanticType. Reject duplicate field blocks that set the same leaf differently inside one registration.

- [ ] **Step 7: Run GREEN**

Run: `./gradlew :wow-query:check --stacktrace`

Expected: merger, skeleton, dynamic descendants, Metadata projection, DSL and error-code tests pass.

- [ ] **Step 8: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema \
  wow-query/src/test/kotlin/me/ahoo/wow/query/schema
git commit -m "feat(query): negotiate logical query schemas"
```

---

### Task 3: Bean/约定文件 Sources 与每服务 Provider 生命周期

**Files:**
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaSources.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaProvider.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaSourcesTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/DefaultQueryModelSchemaProviderTest.kt`
- Create: `wow-query/src/test/resources/wow-query-schema/test-context/test-aggregate/snapshot.json`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryService.kt`

**Interfaces:**
- Consumes: Task 2 source/declaration/merger/final models.
- Produces: `BeanQuerySchemaSource`, `WorkingDirectoryQuerySchemaSource`, `ClasspathQuerySchemaSource`, `QuerySchemaBackendAdapter`, `QueryModelSchemaProvider`, `DefaultQueryModelSchemaProvider`, `requiredQueryModelSchemaProvider()`.

- [ ] **Step 1: Write RED source tests**

Cover exact context matching for Bean registrations, missing file as empty Flux, malformed file as QuerySchemaUnavailableException, path traversal rejection, ClassLoader returning two same-name resources, equal declarations accepted, conflicting same-priority resources rejected by the merger, and refresh rereading changed file content.

```kotlin
@Test
fun `bean source should not leak registrations across aggregates`() {
    val source = BeanQuerySchemaSource(listOf(orderRegistration, cartRegistration))
    source.load(orderContext).collectList().block()!!
        .assert().containsExactly(orderRegistration.declaration)
}
```

- [ ] **Step 2: Write RED lifecycle tests**

Use a counting Source and counting Adapter. Verify two concurrent `schema()` subscribers load once, refresh success publishes the new immutable object, refresh failure returns the exception and subsequent schema returns the previous object, and two Provider instances never share final cache state.

```kotlin
StepVerifier.create(Mono.zip(provider.schema(), provider.schema()))
    .assertNext { pair -> pair.t1.assert().isSameAs(pair.t2) }
    .verifyComplete()
loads.get().assert().isEqualTo(1)
```

- [ ] **Step 3: Run RED**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaSourcesTest" --tests "me.ahoo.wow.query.schema.DefaultQueryModelSchemaProviderTest" --stacktrace`

Expected: source/provider classes are unresolved.

- [ ] **Step 4: Implement convention Sources**

```kotlin
private fun QuerySchemaContext.resourcePath(): String =
    "wow-query-schema/${namedAggregate.contextName}/${namedAggregate.aggregateName}/" +
        "${model.value.lowercase(Locale.ROOT)}.json"
```

Validate each path segment with `require(segment.isNotBlank() && '/' !in segment && '\\' !in segment && segment != "." && segment != "..")`. WorkingDirectory uses `Path.of("config").resolve(resourcePath())`; expose only an internal base-Path constructor for tests. Classpath uses `ClassLoader.getResources(resourcePath())`, sorts URLs by externalForm for deterministic reading, caches by context on load, and evicts only that context before refresh. Absent working file emits nothing.

Parse convention JSON as ObjectNode instead of deserializing DeclarationValue directly. For every supported property use `node.has(name)` to distinguish absent = Unset from explicit JSON null = Set(null); permit Set(null) only for title, description, enumValues and semanticType. Reject null for valueTypes/nullable/required/cardinality/dynamicChildren, non-object root/fields/field declarations, and unknown top-level or field-declaration keys with QuerySchemaUnavailableException. This keeps the public wire file free of `Set`/`Unset` wrapper objects and makes spelling mistakes fail visibly.

- [ ] **Step 5: Implement Provider lifecycle and backend refresh contract**

```kotlin
interface QuerySchemaBackendAdapter {
    fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema>
    fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = resolve(logicalSchema)
}

interface QueryModelSchemaProvider {
    fun schema(): Mono<QueryModelSchema>
    fun refresh(): Mono<QueryModelSchema>
}
```

Default provider loads System declaration, collects each Source result with its priority, merges, then calls adapter resolve/refresh. Use an AtomicReference for the published Schema and a separate AtomicReference for the cached in-flight first-load Mono. Cache that Mono for concurrent subscribers, clear the in-flight reference in `doFinally` so a failed first load can retry, and publish the immutable Schema only in `doOnNext`. Refresh builds a separate Mono and replaces the published reference only on success; never clear a prior successful value in `doOnError`.

Add to SnapshotQueryService.kt:

```kotlin
fun SnapshotQueryService<*>.requiredQueryModelSchemaProvider(): QueryModelSchemaProvider =
    this as? QueryModelSchemaProvider
        ?: throw QuerySchemaUnavailableException("Snapshot query service [$name] does not provide QueryModelSchema.")
```

- [ ] **Step 6: Run GREEN**

Run: `./gradlew :wow-query:check --stacktrace`

Expected: all source and concurrency tests pass without sleeps or global caches.

- [ ] **Step 7: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema \
  wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryService.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/schema \
  wow-query/src/test/resources/wow-query-schema
git commit -m "feat(query): load and cache query schemas"
```

---

### Task 4: Jackson/JSON Schema 推断与 @QueryTemporal 增强

**Files:**
- Modify: `wow-schema/build.gradle.kts`
- Create: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSource.kt`
- Create: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSourceTest.kt`

**Interfaces:**
- Consumes: Task 2 QuerySchemaSource and Task 1 QueryTemporal; existing SchemaGeneratorBuilder/Jackson configuration.
- Produces: `JsonQuerySchemaSource` at priority 100, with annotation semantic overlay embedded in the generated property schema.

- [ ] **Step 1: Add the existing-module dependency and write RED migration tests**

Add `api(project(":wow-query"))` to wow-schema. Port the existing AggregatedFieldPaths fixtures into JsonQuerySchemaSourceTest and assert full QueryFieldDeclaration leaves rather than string-only paths.

```kotlin
@Test
fun `integer temporal annotation should override structural inference`() {
    val declaration = source.load(context).single().block()!!
    declaration.fields.getValue(LogicalField("state.createdAt")).assert {
        valueTypes.isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        semanticType.isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.SECONDS)))
    }
}

private data class TemporalState(
    @QueryTemporal(TimeUnit.SECONDS) val createdAt: Long,
)
```

Construct the Source in tests with an internal state type resolver:

```kotlin
private val source = JsonQuerySchemaSource { TemporalState::class.java }
private val context = QuerySchemaContext(
    MaterializedNamedAggregate("test-context", "test-aggregate"),
    QueryModel.SNAPSHOT,
)
```

Cover scalar/object/array, nullable/required/enum/title/description, Jackson rename, JsonUnwrapped, writeOnly, custom serializer opaque shape, `$ref`, allOf/anyOf/oneOf, polymorphic discriminator, recursive cycles, additionalProperties, illegal LogicalField segments, native date formats and annotation-on-non-integer conflict.

- [ ] **Step 2: Run RED**

Run: `./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.query.JsonQuerySchemaSourceTest" --stacktrace`

Expected: JsonQuerySchemaSource is unresolved.

- [ ] **Step 3: Emit temporal annotation metadata through Victools**

Give JsonQuerySchemaSource an internal primary constructor accepting `(QuerySchemaContext) -> Class<*>` and a public no-arg secondary constructor using `requiredAggregateType().aggregateMetadata().state.aggregateType`. This is a test seam, not a Spring property or runtime loading-location option.

Use `InstanceAttributeOverrideV2<FieldScope>` and the corresponding MethodScope override to add one internal attribute to the generated property:

```kotlin
private const val TEMPORAL_UNIT = "x-wow-query-temporal-unit"

override fun overrideInstanceAttributes(
    attributes: ObjectNode,
    scope: FieldScope,
    context: SchemaGenerationContext,
) {
    scope.getAnnotationConsideringFieldAndGetterIfSupported(QueryTemporal::class.java)
        ?.let { attributes.put(TEMPORAL_UNIT, it.timeUnit.name) }
}
```

Keep the existing custom-serializer opaque-definition logic from TypeFieldPaths. Do not inspect raw Kotlin property names after schema generation; read the internal attribute from the Jackson-shaped property node so rename/unwrapped rules remain authoritative.

- [ ] **Step 4: Implement unbounded, cycle-safe schema traversal**

Traverse properties, items, `$ref`, allOf, anyOf and oneOf with a set of currently resolving reference strings. Prefix all state fields with `state`. For each property:

```kotlin
QueryFieldDeclaration(
    title = Set(propertySchema.textValueOrNull("title")),
    description = Set(propertySchema.textValueOrNull("description")),
    enumValues = Set(propertySchema.enumValuesOrNull()),
    valueTypes = Set(propertySchema.nonNullValueTypes()),
    nullable = Set(propertySchema.allowsNull()),
    required = Set(propertyName in parentRequired),
    cardinality = Set(if (propertySchema.isArrayShape()) QueryCardinality.MANY else QueryCardinality.SINGLE),
    semanticType = Set(propertySchema.inferredTemporal()),
    dynamicChildren = Set(propertySchema.hasAdditionalProperties()),
)
```

Define these private helpers in JsonQuerySchemaSource.kt with the following exact contracts:

```kotlin
private fun JsonNode.textValueOrNull(name: String): String? =
    get(name)?.takeIf(JsonNode::isString)?.stringValue()

private fun JsonNode.enumValuesOrNull(): List<JsonNode>? =
    get("enum")?.takeIf(JsonNode::isArray)?.toList()

private fun JsonNode.nonNullValueTypes(): Set<QueryValueType>
private fun JsonNode.allowsNull(): Boolean
private fun JsonNode.isArrayShape(): Boolean
private fun JsonNode.inferredTemporal(): QuerySemanticType?
private fun JsonNode.hasAdditionalProperties(): Boolean
```

`nonNullValueTypes` maps JSON string/integer/number/boolean/object to the Task 1 constants and ignores null/array; for arrays it reads items. `allowsNull` searches direct type and composition branches for null. `inferredTemporal` maps date/date-time formats to Temporal.Date and otherwise returns null. `hasAdditionalProperties` is true only for an object-valued schema or boolean true additionalProperties declaration.

Emit a separate `state` root declaration containing only System-Unset leaves that the aggregate schema can legitimately contribute: title, description, enumValues and dynamicChildren. Leave its valueTypes, cardinality, required, nullable and semanticType Unset because System owns the stable `SINGLE + OBJECT` root contract. Emit full structural leaves for every `state.*` property.

For array properties infer valueTypes/semanticType from items while keeping the field cardinality MANY. Apply `x-wow-query-temporal-unit` only after structural inference and require the effective non-null type set to equal `{INTEGER}`; otherwise throw QuerySchemaConflictException. Retain the recursive field itself and stop only the repeated descendant expansion.

- [ ] **Step 5: Run GREEN and prove equivalence before later atomic deletion**

Run: `./gradlew :wow-schema:check --stacktrace`

Expected: migrated Query Schema tests pass. Keep TypeFieldPaths.kt and AggregatedFieldPathsTest.kt temporarily because wow-openapi still imports them; Task 10 deletes the old source, OpenAPI consumers and snapshots atomically so every intermediate commit remains buildable.

- [ ] **Step 6: Commit**

```bash
git add wow-schema/build.gradle.kts \
  wow-schema/src/main/kotlin/me/ahoo/wow/schema/query \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/query
git commit -m "feat(schema): infer logical query schemas"
```

---

### Task 5: 请求兼容性、物理路径解析与 validation mode

**Files:**
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationModeTest.kt`

**Interfaces:**
- Consumes: final QueryModelSchema/bindings from Task 2 and existing FilterExpression/Queryable/AggregationQuery contracts.
- Produces: `QuerySchemaValidationMode`, `QuerySchemaResolution<T>`, `QuerySchemaResolver`, Provider extensions for Single/List/Paged/filter/Aggregation requests.

- [ ] **Step 1: Write RED compatibility matrix tests**

```kotlin
@Test
fun `validation modes should accept only their documented levels`() {
    QuerySchemaValidationMode.COMPATIBLE.accepts(QueryCompatibilityLevel.EXACT).assert().isTrue()
    QuerySchemaValidationMode.COMPATIBLE.accepts(QueryCompatibilityLevel.COMPATIBLE).assert().isTrue()
    QuerySchemaValidationMode.COMPATIBLE.accepts(QueryCompatibilityLevel.INCOMPATIBLE).assert().isFalse()
    QuerySchemaValidationMode.STRICT.accepts(QueryCompatibilityLevel.EXACT).assert().isTrue()
    QuerySchemaValidationMode.STRICT.accepts(QueryCompatibilityLevel.COMPATIBLE).assert().isFalse()
}
```

Add resolver tests for exact physical binding, unknown field without dynamic ancestor = COMPATIBLE/unmodified, dynamic ancestor suffix binding = EXACT, known wrong capability = INCOMPATIBLE, nested ElementMatch relative fields, projection PRESENCE, sort SORT, terms/numeric/temporal aggregation, and model-level full-text with nonempty fields = COMPATIBLE.

- [ ] **Step 2: Write RED Provider fallback tests**

Use a Provider that errors with QuerySchemaUnavailableException. Verify COMPATIBLE returns the original request, STRICT propagates the exception, and QuerySchemaConflictException propagates in both modes.

- [ ] **Step 3: Run RED**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaResolverTest" --tests "me.ahoo.wow.query.schema.QuerySchemaValidationModeTest" --stacktrace`

Expected: resolver and mode classes are unresolved.

- [ ] **Step 4: Implement temporary resolution values and level combination**

```kotlin
data class QuerySchemaResolution<T>(
    val value: T,
    val compatibility: QueryCompatibilityLevel,
)

private fun Iterable<QueryCompatibilityLevel>.combined(): QueryCompatibilityLevel = when {
    QueryCompatibilityLevel.INCOMPATIBLE in this -> QueryCompatibilityLevel.INCOMPATIBLE
    QueryCompatibilityLevel.COMPATIBLE in this -> QueryCompatibilityLevel.COMPATIBLE
    else -> QueryCompatibilityLevel.EXACT
}

fun <T> QuerySchemaResolution<T>.requireAccepted(mode: QuerySchemaValidationMode): T {
    if (!mode.accepts(compatibility)) {
        throw QuerySchemaValidationException("Query compatibility [$compatibility] is rejected by mode [$mode].")
    }
    return value
}
```

- [ ] **Step 5: Implement field/capability mapping and physical rewrites**

Map filter types exactly:

```text
EQ/NE/IN/NOT_IN/CONTAINS_ALL -> EXACT_MATCH
CONTAINS/STARTS_WITH/ENDS_WITH -> LITERAL_MATCH
GT/GTE/LT/LTE/BETWEEN and normalized relative-time ranges -> RANGE
IS_EMPTY/IS_NULL/IS_NOT_NULL/EXISTS/NOT_EXISTS -> PRESENCE
ELEMENT_MATCH -> ELEMENT_SCOPE plus recursive predicate capabilities
SEARCH TERMS/PHRASE -> FULL_TEXT_TERMS/FULL_TEXT_PHRASE
Sort -> SORT
Projection include/exclude -> PRESENCE
Terms/Histogram/DateHistogram -> AGGREGATE_TERMS/AGGREGATE_NUMERIC/AGGREGATE_TEMPORAL
Numeric expression fields -> AGGREGATE_NUMERIC
```

Root metadata filters without a LogicalField are EXACT when the System field has a matching binding. Within ElementMatch, resolve the container to its physical path, validate child fields against the full logical parent path, then emit child paths relative to the physical container so both Mongo `$elemMatch` and Elasticsearch nested compilation remain correct.

For SearchFilter with fields: exact field bindings produce rewritten fields and EXACT; absent field bindings plus matching model capability replace fields with an empty set so the backend executes model-level search and produce COMPATIBLE; no model/field capability produces INCOMPATIBLE. This makes Mongo's ignored field restriction explicit and prevents Elasticsearch from compiling unmapped requested fields.

- [ ] **Step 6: Implement Provider request extensions**

```kotlin
fun QueryModelSchemaProvider.resolve(
    query: ISingleQuery,
    mode: QuerySchemaValidationMode,
): Mono<ISingleQuery> = schema()
    .map { QuerySchemaResolver(it).resolve(query).requireAccepted(mode) }
    .onErrorResume(QuerySchemaUnavailableException::class.java) { error ->
        if (mode == QuerySchemaValidationMode.COMPATIBLE) Mono.just(query) else Mono.error(error)
    }
```

Implement these additional exact overloads in the same file:

```kotlin
fun QueryModelSchemaProvider.resolve(query: IListQuery, mode: QuerySchemaValidationMode): Mono<IListQuery>
fun QueryModelSchemaProvider.resolve(query: IPagedQuery, mode: QuerySchemaValidationMode): Mono<IPagedQuery>
fun QueryModelSchemaProvider.resolve(filter: FilterExpression, mode: QuerySchemaValidationMode): Mono<FilterExpression>
fun QueryModelSchemaProvider.resolve(
    query: AggregationQuery,
    mode: QuerySchemaValidationMode,
): Mono<Optional<QueryModelSchema>>
```

For Single/List/Paged, QuerySchemaResolver combines independently resolved filter, projection and sort levels and returns a new existing `SingleQuery`, `ListQuery` or `PagedQuery`, preserving limit/pagination. Filter overload returns the rewritten FilterExpression. Aggregation overload validates filter, elements, groups and expression fields, calls requireAccepted(mode), then returns `Optional.of(schema)` instead of rewriting the request; backend aggregation compilers receive both original query and Schema so temporal semantics remain addressable by logical field. On QuerySchemaUnavailableException, COMPATIBLE returns `Optional.empty()` and the service calls its existing pre-Schema compiler path; STRICT propagates the exception. Other overloads return the original request only for the same unavailable-only COMPATIBLE fallback.

- [ ] **Step 7: Run GREEN**

Run: `./gradlew :wow-query:check --stacktrace`

Expected: all resolver, fallback, unknown/dynamic, full-text and mode matrix tests pass.

- [ ] **Step 8: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationModeTest.kt
git commit -m "feat(query): validate requests against query schemas"
```

---

### Task 6: MongoDB Adapter、服务绑定与 Schema-aware 编译

**Files:**
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapter.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapterTest.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryService.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceFactory.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt`
- Modify: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceTest.kt`

**Interfaces:**
- Consumes: Task 3 Provider/Adapter and Task 5 resolver.
- Produces: MongoQuerySchemaAdapter and built-in MongoSnapshotQueryService implementing QueryModelSchemaProvider by delegation.

- [ ] **Step 1: Write RED Adapter tests**

Mock MongoCollection.listIndexes and optional MongoDatabase.listCollections facts. Verify aggregateId binds to `_id`, ordinary paths follow SnapshotFieldConverter, no validator leaves storageType null, validator bsonType maps when present, text index adds only model-level FULL_TEXT_TERMS/FULL_TEXT_PHRASE, MANY+OBJECT gets ELEMENT_SCOPE, and the adapter never invokes find/sample.

```kotlin
adapter.resolve(logicalSchema).test()
    .assertNext { schema ->
        schema.fields.getValue(LogicalField("aggregateId"))
            .bindings.getValue(QueryCapability.EXACT_MATCH)
            .physicalPath.assert().isEqualTo("_id")
        schema.capabilities.assert().contains(QueryCapability.FULL_TEXT_TERMS)
    }
    .verifyComplete()
```

- [ ] **Step 2: Write RED service/compiler tests**

Verify strict mode rejects an unknown field before calling MongoCollection, compatible mode preserves current fallback, physical bindings reach find/sort/projection, Mongo text search with fields is accepted as COMPATIBLE, refresh rereads indexes, and Temporal.Epoch(MILLISECONDS) DateHistogram compiles a safe `$convert`/`$dateTrunc` pipeline.

- [ ] **Step 3: Run RED**

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapterTest" --tests "me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest" --stacktrace`

Expected: Adapter is unresolved and compiler has no Schema argument.

- [ ] **Step 4: Implement Mongo physical facts and bindings**

```kotlin
class MongoQuerySchemaAdapter(
    private val collection: MongoCollection<Document>,
    private val database: MongoDatabase? = null,
) : QuerySchemaBackendAdapter {
    override fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = loadFacts(logicalSchema)
    override fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = loadFacts(logicalSchema)
}
```

`loadFacts(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema>` zips the reactive index list and optional validator lookup, then calls one pure `bind(logicalSchema, facts)` function. Unit-test bind separately from driver errors; wrap driver/parse failures in QuerySchemaUnavailableException with the original cause.

Use `collection.listIndexes()` to detect any index key whose value is `"text"`. If database is available, use collection namespace with `database.listCollections()` to read `options.validator.$jsonSchema`; parse only declared bsonType/properties/items and leave unknown storageType null. Derive capabilities from logical valueTypes/cardinality/semanticType, then create QueryFieldBinding with SnapshotFieldConverter.convert(logicalPath). No document read is allowed.

- [ ] **Step 5: Add reactive resolution hooks to AbstractMongoQueryService**

Add protected identity overloads for ISingleQuery, IListQuery, IPagedQuery and FilterExpression. Resolve before constructing each driver publisher:

```kotlin
protected open fun resolve(query: ISingleQuery): Mono<ISingleQuery> = Mono.just(query)

private fun singleDocument(query: ISingleQuery): Mono<Document> =
    resolve(query).flatMap { resolved ->
        findDocument(resolved).limit(1).first().toMono()
    }
```

Apply the same pattern to list, paged and count without blocking. EventStream services inherit identity behavior.

- [ ] **Step 6: Bind Provider in Mongo factory/service**

Extend MongoSnapshotQueryServiceFactory constructor with `schemaSources: List<QuerySchemaSource> = emptyList()` and `validationMode: QuerySchemaValidationMode = COMPATIBLE`. In createQueryService construct one DefaultQueryModelSchemaProvider using QuerySchemaContext(namedAggregate.materialize(), SNAPSHOT), MongoQuerySchemaAdapter(collection, database), and pass it to the service.

MongoSnapshotQueryService delegates QueryModelSchemaProvider and overrides the four AbstractMongo resolve hooks with Task 5 Provider extensions. Keep its public direct constructor working by constructing a System-only Provider with `database = null`; do not create a global Provider cache.

- [ ] **Step 7: Compile aggregation with Schema semantics**

Change signature to:

```kotlin
fun compile(query: AggregationQuery, schema: QueryModelSchema? = null): List<Bson>
```

When schema is present, resolve element/group/metric fields through Schema bindings. When Task 5 returns Optional.empty in COMPATIBLE mode, null preserves the existing convention compiler path. DateHistogram with Schema requires `Temporal.Date` or `Temporal.Epoch`; Epoch converts integral scalar or singleton-array values to epoch millis with `$convert`, uses floor division for negative nanos/micros, rejects invalid/multi-valued data as null, then applies `$dateTrunc`. Reuse the existing finite numeric guards; do not store pipeline expressions in QueryFieldBinding.

- [ ] **Step 8: Run unit and real Mongo GREEN**

Run:

```bash
./gradlew :wow-mongo:check --stacktrace
./gradlew :wow-mongo:integrationTest --tests "me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceTest" --stacktrace
```

Expected: adapter/compiler tests and shared SnapshotQueryServiceSpec pass; no blocking call is introduced into query paths.

- [ ] **Step 9: Commit**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapter.kt \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryService.kt \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceFactory.kt \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapterTest.kt \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt \
  wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceTest.kt
git commit -m "feat(mongo): bind snapshot queries to negotiated schemas"
```

---

### Task 7: Elasticsearch Adapter、mapping 刷新与 Schema-aware 编译

**Files:**
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaAdapter.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaAdapterTest.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryService.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryService.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceFactory.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolverTest.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotMappingQueryTest.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceTest.kt`
- Delete: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchMappingEndpointAutoConfiguration.kt`
- Delete: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchMappingEndpointAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**Interfaces:**
- Consumes: Task 3 Provider/Adapter and Task 5 resolver.
- Produces: ElasticsearchQuerySchemaAdapter and built-in ElasticsearchSnapshotQueryService implementing QueryModelSchemaProvider by delegation.

- [ ] **Step 1: Write RED Adapter tests from current mapping fixtures**

Move the capability expectations currently encoded through ElasticsearchFieldUsage into Schema assertions: text/keyword multi-field, phrase support, sort/doc_values, date/date_nanos, numeric, nested, flattened, runtime fields, aliases, indexed=false and ambiguous multi-fields.

```kotlin
val name = schema.fields.getValue(LogicalField("state.name"))
name.bindings.getValue(QueryCapability.FULL_TEXT_TERMS).physicalPath.assert().isEqualTo("state.name")
name.bindings.getValue(QueryCapability.EXACT_MATCH).physicalPath.assert().isEqualTo("state.name.keyword")
name.bindings.getValue(QueryCapability.SORT).physicalPath.assert().isEqualTo("state.name.keyword")
```

- [ ] **Step 2: Write RED refresh and compiler tests**

Verify resolver refresh returns only ElasticsearchIndexMapping, concurrent initial loads coalesce, Provider refresh obtains the new mapping and atomically replaces final Schema, strict unknown fields fail before search, and numeric epoch DateHistogram produces request-local date runtime mapping with field name in params.

- [ ] **Step 3: Run RED**

Run: `./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterTest" --tests "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationCompilerTest" --stacktrace`

Expected: Adapter is unresolved and legacy resolver result still exposes changed.

- [ ] **Step 4: Simplify mapping resolver to physical facts only**

Remove ElasticsearchMappingRefreshResult and all equality/changed calculations. Keep the in-flight refresh Mono and mapping cache:

```kotlin
fun refresh(indexName: String): Mono<ElasticsearchIndexMapping> =
    refreshes.computeIfAbsent(indexName) {
        load(indexName)
            .doOnNext { mappings[indexName] = it }
            .doFinally { refreshes.remove(indexName) }
            .cache()
    }
```

Expose mapped field facts as internal read-only data for the Adapter. Delete filter/sort resolution from ElasticsearchIndexMapping after all compiler callers move to QueryModelSchema.

Delete ElasticsearchMappingEndpointAutoConfiguration.kt and its test in the same change, and remove its AutoConfiguration.imports entry before removing ElasticsearchMappingRefreshResult. This keeps the repository buildable while eliminating the only external consumer of the obsolete changed result; endpoint documentation is updated in Task 10.

- [ ] **Step 5: Implement Elasticsearch bindings**

For each logical field, inspect exact mapping, flattened ancestor and multi-fields. Bind capabilities to the narrowest valid path: text for FULL_TEXT, keyword/exact for EXACT/LITERAL/SORT, numeric/date for RANGE, nested for ELEMENT_SCOPE, doc-values-capable scalar fields for aggregations. Set QueryStorageType from Elasticsearch Property.Kind/runtime type. Model-level full-text exists when the mapping can execute an unscoped multi_match; field bindings remain separate.

Adapter `resolve` uses currentOrLoad(indexName); `refresh` uses resolver.refresh(indexName). Both require exactly one physical mapping and translate errors to QuerySchemaUnavailableException with cause.

- [ ] **Step 6: Add Schema resolution hooks and Provider delegation**

As in Mongo, add protected identity request-resolution overloads to AbstractElasticsearchQueryService and call them before compile/search/count. Remove its direct indexMappingResolver branch. ElasticsearchSnapshotQueryService overrides through Task 5 Provider extensions; EventStream services remain identity.

Extend factory constructor with sources and mode, build one Provider per cached service, and remove `refreshIndexMapping()` from service/factory because refresh is now QueryModelSchemaProvider.refresh().

- [ ] **Step 7: Compile aggregation through Schema**

Change compiler signature to `compile(query, schema: QueryModelSchema? = null)`. Null is used only for the approved unavailable+COMPATIBLE fallback and preserves the existing convention path. With Schema, replace ElasticsearchFieldUsage calls with binding lookup. For Temporal.Epoch, create one request-local RuntimeField of type Date per date group using the proven overflow-safe Painless conversion: field name and multiplier/divisor are params, invalid/non-integral/multi-valued/overflow values do not emit, and negative sub-millisecond values use floor adjustment. Temporal.Formatted is INCOMPATIBLE for DateHistogram in this phase.

- [ ] **Step 8: Run unit and real Elasticsearch GREEN**

Run:

```bash
./gradlew :wow-elasticsearch:check --stacktrace
./gradlew :wow-elasticsearch:integrationTest --tests "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceTest" --stacktrace
```

Expected: mapping adapter, compiler, refresh and shared SnapshotQueryServiceSpec pass; no changed/version result remains.

- [ ] **Step 9: Commit**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaAdapter.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryService.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryService.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceFactory.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaAdapterTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolverTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotMappingQueryTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt \
  wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceTest.kt \
  wow-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
git add -u wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchMappingEndpointAutoConfiguration.kt \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchMappingEndpointAutoConfigurationTest.kt
git commit -m "feat(elasticsearch): bind snapshot queries to negotiated schemas"
```

---

### Task 8: Spring Boot 来源、配置与 Backend Factory 装配

**Files:**
- Modify: `wow-spring-boot-starter/build.gradle.kts`
- Create: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryProperties.kt`
- Create: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QuerySchemaAutoConfiguration.kt`
- Create: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/query/QuerySchemaAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfiguration.kt`
- Modify tests for both backend auto-configurations.

**Interfaces:**
- Consumes: Tasks 3/4 Sources and Tasks 6/7 factory constructors.
- Produces: default Source beans, QueryProperties, developer QuerySchemaRegistration Bean collection and backend injection.

- [ ] **Step 1: Write RED ApplicationContext tests**

Verify default validation mode COMPATIBLE, property binding to STRICT, one bean each for System-independent built-in Sources, two QuerySchemaRegistration beans both reach BeanQuerySchemaSource, missing convention files do not fail startup, and Mongo/ES factories receive the same Source list without creating a global final Schema cache.

- [ ] **Step 2: Run RED**

Run: `./gradlew :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.query.QuerySchemaAutoConfigurationTest" --stacktrace`

Expected: QuerySchemaAutoConfiguration is unresolved.

- [ ] **Step 3: Add module dependencies and configuration**

Add `implementation(project(":wow-schema"))` to the starter. Define:

```kotlin
@ConfigurationProperties(prefix = QueryProperties.PREFIX)
class QueryProperties(
    var schema: Schema = Schema(),
) {
    data class Schema(
        var validationMode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
    )
    companion object { const val PREFIX = "wow.query" }
}
```

- [ ] **Step 4: Implement focused QuerySchemaAutoConfiguration**

Register JsonQuerySchemaSource, WorkingDirectoryQuerySchemaSource, ClasspathQuerySchemaSource and a BeanQuerySchemaSource built from `ObjectProvider<QuerySchemaRegistration>.toList()`. Do not register SystemQuerySchemaSource as an extension Source; Providers construct/use the global System input directly.

Import this auto-configuration before MongoEventSourcingAutoConfiguration and ElasticsearchEventSourcingAutoConfiguration in AutoConfiguration.imports. Add QuerySchemaAutoConfiguration to each backend auto-configuration's `after` list so their factory methods can consume the complete Source list. Keep QueryAutoConfiguration responsible only for query handlers/filters/fallback factories; do not reorder it ahead of storage factories because its ConditionalOnMissingBean fallbacks must remain last.

- [ ] **Step 5: Inject Sources and mode into backend factories**

Add `sources: List<QuerySchemaSource>` and QueryProperties to Mongo/Elasticsearch snapshot factory bean methods and pass `queryProperties.schema.validationMode`. Do not alter EventStream factories or routing keys.

- [ ] **Step 6: Run GREEN**

Run: `./gradlew :wow-spring-boot-starter:check --stacktrace`

Expected: source/config/backend auto-configuration tests pass and the generated configuration metadata contains `wow.query.schema.validation-mode` with COMPATIBLE default.

- [ ] **Step 7: Commit**

```bash
git add wow-spring-boot-starter/build.gradle.kts \
  wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query \
  wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfiguration.kt \
  wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfiguration.kt \
  wow-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/query/QuerySchemaAutoConfigurationTest.kt \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfigurationTest.kt \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfigurationTest.kt
git commit -m "feat(starter): configure query schema sources"
```

---

### Task 9: 聚合 Schema GET/refresh、错误映射与 OpenAPI 合同

**Files:**
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/BuiltInHttpRoutes.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/snapshot/SnapshotRouteContributor.kt`
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotSchemaHandlerFunction.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotSchemaHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/ErrorHttpStatusMapping.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/exception/ErrorHttpStatusMappingTest.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/QueryRouteModule.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`

**Interfaces:**
- Consumes: SnapshotQueryServiceFactory and QueryModelSchemaProvider from earlier tasks.
- Produces: handler keys `Snapshot.SCHEMA`/`Snapshot.SCHEMA_REFRESH`, route IDs ending `.snapshot_schema.get`/`.snapshot_schema.refresh`, Metadata HTTP responses.

- [ ] **Step 1: Write RED OpenAPI route tests**

```kotlin
@Test
fun `snapshot schema routes should be aggregate scoped and independently identified`() {
    val get = openAPI.paths["/cart/snapshot/schema"]!!.get
    val refresh = openAPI.paths["/cart/snapshot/schema/refresh"]!!.post
    get.operationId.assert().isEqualTo("example.cart.snapshot_schema.get")
    refresh.operationId.assert().isEqualTo("example.cart.snapshot_schema.refresh")
    get.responses["200"]!!.content[Https.MediaType.APPLICATION_JSON]!!.schema.`$ref`
        .assert().isEqualTo("#/components/schemas/wow.api.query.schema.QueryModelSchemaMetadata")
}
```

Assert routes do not generate tenant/owner/id variants and expose 400/500/503 responses.

- [ ] **Step 2: Write RED WebFlux handler/error tests**

Use a recording SnapshotQueryServiceFactory whose service implements Provider. GET must call schema once and return sorted public Metadata; refresh must call refresh once; a custom service without Provider returns QuerySchemaUnavailable; response JSON contains no physicalPath/storageType. Assert error-code mapping 400/500/503.

- [ ] **Step 3: Run RED**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.ExampleDomainOpenAPITest" --stacktrace
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.snapshot.SnapshotSchemaHandlerFunctionTest" --stacktrace
```

Expected: route keys/handlers are unresolved.

- [ ] **Step 4: Add route contracts**

Add BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA and SCHEMA_REFRESH. In SnapshotRouteContributor add exactly two base aggregate routes:

```kotlin
snapshotRoute(
    handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA,
    resourceName = "snapshot_schema",
    operation = "get",
    method = Https.Method.GET,
    appendTenantPath = false,
    appendOwnerPath = false,
    appendPathSuffix = "snapshot/schema",
    responses = schemaResponses(componentContext),
)
```

Define the response helper explicitly:

```kotlin
private fun schemaResponses(componentContext: OpenAPIComponentContext): List<HttpResponse> = listOf(
    HttpResponse(
        statusCode = Https.Code.OK,
        headers = listOf(componentContext.errorCodeHeaderRef()),
        content = listOf(
            HttpContent(
                Https.MediaType.APPLICATION_JSON,
                HttpSchema.TypeRef(QueryModelSchemaMetadata::class.java),
            ),
        ),
    ),
    HttpResponse(Https.Code.BAD_REQUEST),
    HttpResponse(Https.Code.INTERNAL_SERVER_ERROR),
    HttpResponse(Https.Code.SERVICE_UNAVAILABLE),
)
```

Refresh uses operation `refresh`, POST and suffix `snapshot/schema/refresh`. Response schema is HttpSchema.TypeRef(QueryModelSchemaMetadata::class.java).

- [ ] **Step 5: Implement handlers and factories**

```kotlin
private fun provider(
    metadata: AggregateMetadata<*, *>,
): QueryModelSchemaProvider = snapshotQueryServiceFactory
    .create<Any>(metadata)
    .requiredQueryModelSchemaProvider()

override fun handle(request: ServerRequest): Mono<ServerResponse> =
    provider(aggregateMetadata).schema()
        .map(QueryModelSchema::toMetadata)
        .toServerResponse(request, exceptionHandler)
```

Refresh handler differs only by calling refresh(). Add both factories to QueryRouteModule. Inject SnapshotQueryServiceFactory into WebFluxAutoConfiguration.queryRouteModule; do not send metadata requests through the query filter chain because no user query is executed.

- [ ] **Step 6: Register HTTP mappings**

Register exception error codes in ErrorHttpStatusMapping with BAD_REQUEST, INTERNAL_SERVER_ERROR and SERVICE_UNAVAILABLE. Preserve the existing WowException to ErrorInfo path; do not add a second exception converter.

- [ ] **Step 7: Run GREEN**

Run: `./gradlew :wow-webflux:check :wow-openapi:check :wow-spring-boot-starter:check --stacktrace`

Expected: route, handler, public DTO and error mapping tests pass.

- [ ] **Step 8: Commit**

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/BuiltInHttpRoutes.kt \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/snapshot/SnapshotRouteContributor.kt \
  wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotSchemaHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/ErrorHttpStatusMapping.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotSchemaHandlerFunctionTest.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/exception/ErrorHttpStatusMappingTest.kt \
  wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/QueryRouteModule.kt \
  wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt
git commit -m "feat(webflux): expose aggregate query schemas"
```

---

### Task 10: 删除静态字段目录与旧 Elasticsearch Actuator

**Files:**
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/QueryComponent.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/QueryContractComponentSupport.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/snapshot/SnapshotRouteContributor.kt`
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Delete: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/TypeFieldPaths.kt`
- Delete: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/AggregatedFieldPathsTest.kt`
- Modify: `documentation/docs/zh/guide/query.md`
- Modify: `documentation/docs/en/guide/query.md`
- Modify: `documentation/docs/zh/guide/extensions/webflux.md`
- Modify: `documentation/docs/en/guide/extensions/webflux.md`
- Modify: `documentation/docs/zh/guide/extensions/elasticsearch.md`
- Modify: `documentation/docs/en/guide/extensions/elasticsearch.md`

**Interfaces:**
- Consumes: Task 9 runtime Schema endpoints.
- Produces: generic query request bodies only; no static field enum or Actuator refresh contract.

- [ ] **Step 1: Replace old positive tests with negative contract tests**

In ExampleDomainOpenAPITest assert no request body extensions contain `x-wow-query-fields`, no component key ends with `AggregatedFields`, and the generic AggregationQuery/SingleQuery/ListQuery/PagedQuery/FilterExpression schemas remain referenced by Snapshot routes.

Confirm the Task 7 commit already removed WowElasticsearchMappingEndpoint and its AutoConfiguration import; the repository-wide negative scan in Step 6 is the regression gate.

- [ ] **Step 2: Run RED**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.ExampleDomainOpenAPITest" :wow-spring-boot-starter:test --stacktrace`

Expected: existing extensions/components make the new OpenAPI assertions fail; starter tests remain green with the Actuator endpoint already absent from Task 7.

- [ ] **Step 3: Simplify QueryComponent request bodies**

Remove QUERY_FIELDS_EXTENSION, aggregatedFieldsSchema and all `commandAggregatedFieldPaths` imports. Add reusable generic SINGLE_QUERY_KEY and AGGREGATION_QUERY_KEY request bodies alongside existing count/list/paged keys. Update QueryContractComponentSupport and SnapshotRouteContributor to reference generic bodies; remove aggregate-specific request-body helper functions.

- [ ] **Step 4: Delete the old field directory and verify the earlier Actuator removal**

Delete TypeFieldPaths.kt and AggregatedFieldPathsTest.kt after QueryComponent no longer imports them. Verify Task 7 already removed the endpoint, ElasticsearchMappingRefreshResult and refreshIndexMapping methods. Remove compileOnly Actuator usage only if `rg -n "org.springframework.boot.actuate" wow-spring-boot-starter/src/main` finds no remaining production use; otherwise keep the existing dependency for the remaining endpoints.

- [ ] **Step 5: Update documentation to runtime metadata**

Replace all `x-wow-query-fields` guidance with GET `/{aggregate}/snapshot/schema`. Replace the Actuator configuration/curl/changed response with POST `/{aggregate}/snapshot/schema/refresh`, explain independent route authorization, local-instance refresh, old-cache retention on failure and no broadcast. Keep Elasticsearch `view_index_metadata` permission guidance because the backend adapter still reads mapping.

- [ ] **Step 6: Run GREEN and repository-wide deletion scan**

Run:

```bash
./gradlew :wow-openapi:check :wow-spring-boot-starter:check --stacktrace
rg -n "TypeFieldPaths|AggregatedFieldPaths|x-wow-query-fields|AggregatedFields|wowElasticsearchMapping|ElasticsearchMappingEndpoint" \
  wow-schema wow-openapi wow-spring-boot-starter documentation
```

Expected: Gradle checks pass and rg exits with no matches.

- [ ] **Step 7: Commit**

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/QueryComponent.kt \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/QueryContractComponentSupport.kt \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/snapshot/SnapshotRouteContributor.kt \
  wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json \
  documentation/docs/zh/guide/query.md documentation/docs/en/guide/query.md \
  documentation/docs/zh/guide/extensions/webflux.md documentation/docs/en/guide/extensions/webflux.md \
  documentation/docs/zh/guide/extensions/elasticsearch.md documentation/docs/en/guide/extensions/elasticsearch.md
git add -u wow-schema/src/main/kotlin/me/ahoo/wow/schema/TypeFieldPaths.kt \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/AggregatedFieldPathsTest.kt
git commit -m "refactor(query): remove legacy query field discovery"
```

---

### Task 11: 跨后端 TCK、OpenAPI snapshot 与真实 HTTP 验证

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/mock/MockAggregate.kt`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt`
- Modify: Mongo/Elasticsearch integration fixtures as required to install text indexes/mappings.
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Modify: documentation only if runtime paths differ from generated OpenAPI.

**Interfaces:**
- Consumes: complete implementation from Tasks 1–10.
- Produces: backend-neutral behavioral proof and actual service HTTP evidence.

- [ ] **Step 1: Add shared Schema assertions and temporal fixture**

Annotate the Mock State epoch field:

```kotlin
@QueryTemporal(TimeUnit.MILLISECONDS)
val createdAt: Long
```

In SnapshotQueryServiceSpec require the service Provider and assert System fields, state fields, no physical details in Metadata, temporal semantic, exact/range/sort, element scope, terms/numeric/temporal aggregation and refresh object replacement.

- [ ] **Step 2: Add backend-specific full-text and strict/compatible tests**

Mongo integration creates a collection text index and proves empty Search fields = EXACT while nonempty fields execute with COMPATIBLE mode. Elasticsearch integration installs text+keyword/nested/runtime mappings and proves field-specific bindings are EXACT. For both, STRICT rejects a deliberately unknown field before backend execution and COMPATIBLE permits the unknown fallback.

- [ ] **Step 3: Run the narrow module gates**

```bash
./gradlew :wow-api:check :wow-query:check :wow-schema:check --stacktrace
./gradlew :wow-mongo:check :wow-elasticsearch:check --stacktrace
./gradlew :wow-webflux:check :wow-openapi:check :wow-spring-boot-starter:check --stacktrace
```

Expected: every command exits 0 with no failed tests.

- [ ] **Step 4: Run real backend integration gates**

```bash
./gradlew :wow-mongo:integrationTest --stacktrace
./gradlew :wow-elasticsearch:integrationTest --stacktrace
./gradlew :wow-it:integrationTest --stacktrace
```

Expected: MongoDB and Elasticsearch shared TCK cases plus wow-it pass against their real containers.

- [ ] **Step 5: Verify generated OpenAPI snapshot**

Regenerate through the existing ExampleDomainOpenAPITest flow, review the snapshot diff, and confirm:

```bash
rg -n '"/cart/snapshot/schema"|"/cart/snapshot/schema/refresh"|QueryModelSchemaMetadata' \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json
if rg -n 'x-wow-query-fields|AggregatedFields' \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json; then exit 1; fi
```

Expected: both paths and Metadata schema are present; legacy markers are absent.

- [ ] **Step 6: Start an actual example-server distribution and call both endpoints**

Start the existing Mongo test service if localhost:27017 is not already available:

```bash
docker compose -f wow-benchmarks/docker/compose.mongo.yml up -d
```

Start the app in a terminal and preserve its session:

```bash
./gradlew :example-server:run --args='--spring.config.location=classpath:/application.yaml'
```

From another terminal, discover the generated route and exercise it:

```bash
curl --fail http://localhost:8080/v3/api-docs > /tmp/wow-query-schema-openapi.json
curl --fail http://localhost:8080/cart/snapshot/schema > /tmp/wow-cart-query-schema.json
curl --fail -X POST http://localhost:8080/cart/snapshot/schema/refresh \
  > /tmp/wow-cart-query-schema-refreshed.json
```

Check both JSON responses contain model `SNAPSHOT`, System fields and state fields, and do not contain physicalPath/storageType. If generated OpenAPI materializes a different aggregate prefix, use the exact path from `/tmp/wow-query-schema-openapi.json` and update documentation/tests to that generated truth before rerunning module gates.

- [ ] **Step 7: Run documentation build and final diff audit**

```bash
cd documentation
pnpm install --frozen-lockfile
pnpm docs:build
cd ..
git diff --check
git status --short
```

Expected: VitePress build succeeds; only intended implementation/doc files are changed; no build output, credentials, local config or `.superpowers/` files are staged.

- [ ] **Step 8: Commit integration proof**

```bash
git add test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/mock/MockAggregate.kt \
  test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt \
  wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceTest.kt \
  wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceTest.kt \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json
git commit -m "test(query): verify negotiated schemas end to end"
```

---

## Final Verification Checklist

- [ ] `QueryModelSchema` is generated deterministically from System + prioritized aggregate extensions + backend facts.
- [ ] Metadata fields are sorted and contain no physical binding details.
- [ ] MongoDB and Elasticsearch compilers consume only the final Schema, not individual Sources.
- [ ] COMPATIBLE/STRICT behavior matches the three-level matrix for filters, projection, sort, aggregation and full-text.
- [ ] Refresh rereads files/backend facts, atomically replaces on success and retains the old Schema on failure.
- [ ] Convention files and Bean registrations are scoped to exact QuerySchemaContext.
- [ ] `@QueryTemporal` follows Jackson wire shape and rejects non-integer usage.
- [ ] GET/refresh routes exist with separate route IDs and error status mappings 400/500/503.
- [ ] TypeFieldPaths, static OpenAPI field enums/extensions and the Actuator endpoint are absent repository-wide.
- [ ] Narrow checks, real Mongo/Elasticsearch integration, wow-it, OpenAPI snapshot, actual HTTP calls and VitePress build all pass.
