# CursorQuery V9 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 V9 `QueryGateway → QueryBackend` 架构中实现 Snapshot/EventStream 通用游标分页，并通过静态 Mask 排序拒绝删除 cursor encryption key。

**Architecture:** 公共层新增 `CursorQuery`/`CursorPage`；Gateway 复用 QueryFilter、ErrorHandler 与 SchemaMasker，具体 Backend 在存储访问前追加唯一排序、执行 Query Schema 精确解析并拒绝 masked sort。MongoDB 使用 BSON Base64URL keyset，Elasticsearch 使用 JSON Base64URL `search_after`，二者均读取 `size + 1` 且不计算 total。

**Tech Stack:** Kotlin 2.4.10、JVM 17、Reactor、Jackson、MongoDB Reactive Streams Driver、Elasticsearch Java Client、Spring WebFlux、OpenAPI 3.1、JUnit Jupiter、MockK、Wow TCK。

**Spec:** `document/design/2026-08-30-cursor-query-v9-design.md`

## Global Constraints

- 初始基线为 `origin/agent/static-annotation-mask-v9-main` 的 `e42756112`；收尾时已 rebase 到
  `bd6002581`，分支为 `feat/cursor-query-v9`。
- 现有 `feat/cursor-query`、PR #3091、V8 `QueryService` 实现和历史提交不得改写或合并。
- 现有 single/list/paged/count/aggregate 行为、普通 masked filter/sort/projection 语义不变。
- CursorQuery 无状态、仅向后、无 total、无 previous cursor、无 PIT、无跨请求快照一致性。
- Snapshot 有效排序追加 `aggregateId`；EventStream 追加 `id`；拒绝重复字段和 `_score`、`_doc`、`_shard_doc`。
- 所有有效 cursor sort 必须精确解析为已知、`SINGLE` 的 SORT binding，且 `maskRule == null`，
  其逻辑/物理路径也不得命中 masked projection/binding alias；Schema unavailable 必须 fail-closed。
- token 只使用无 padding Base64URL；不得新增 AES、签名、key、key ring、fallback secret、过期时间或服务端 cursor 状态。
- MongoDB 不执行 `countDocuments`/`skip`；Elasticsearch 不设置 `from`/PIT，且 `track_total_hits=false`。
- 不新增依赖、不改 Gradle 模块结构、不手改生成客户端、不运行 `javap`。
- Kotlin 测试使用 `me.ahoo.test.asserts.assert`；每个行为任务严格执行 RED→GREEN 后再提交。
- 每个任务只提交列出的文件；推送、PR、合并不在本计划自动授权范围内。

---

## 文件职责

- `wow-api/.../CursorQuery.kt`、`CursorPage.kt`：稳定公共 wire/API 契约。
- `wow-query/.../CursorQueries.kt`：唯一排序追加与通用稳定性限制。
- `wow-query/.../QuerySchemaResolver.kt`：cursor sort 的 EXACT + SINGLE + unmasked/无 alias 安全判定。
- `wow-query/.../QueryGateway.kt`：V9 filter/error/mask/typed-dynamic cursor 管线。
- `wow-mongo/.../MongoCursorFilterCompiler.kt`：MongoDB 词典序 keyset filter。
- `wow-mongo/.../MongoCursorDocuments.kt`：BSON token、临时 projection 与 CursorPage 映射。
- `wow-elasticsearch/.../ElasticsearchCursorCodec.kt`：FieldValue JSON Base64URL 边界。
- `wow-webflux/.../CursorQueryHandlerFunction.kt`：公共 HTTP Mono CursorPage handler。
- Snapshot/EventStream route factory、OpenAPI contributor、API Client 与 TCK 继续遵循现有一类契约一个文件的布局。

不创建 `CursorQueryService`、cursor store、cursor registry、共享加密 Codec、独立 Spring 配置或新的 capability 模块。

---

### Task 1: 公共 CursorQuery、CursorPage 与 JSON Schema

**Files:**
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/CursorQuery.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/CursorPage.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/QueryProtocol.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/CursorQueryTest.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/CursorPageTest.kt`
- Create: `schema/query/v2/cursor-query.schema.json`
- Create: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/CursorQuerySchemaTest.kt`

**Interfaces:**
- Consumes: `Queryable`, `FilterExpression`, `Projection`, `Sort`, `AggregationQuery.MAX_SORT_FIELDS`。
- Produces: `ICursorQuery`, `CursorQuery`, `CursorPage<T>`，供 Task 2–8 使用。

- [ ] **Step 1: 写失败的 API 边界测试**

`CursorQueryTest.kt`：

```kotlin
class CursorQueryTest {
    @Test
    fun `should use cursor defaults`() {
        val query = CursorQuery(MatchAllFilter)
        query.projection.assert().isEqualTo(Projection.ALL)
        query.sort.assert().isEmpty()
        query.size.assert().isEqualTo(10)
        query.cursor.assert().isNull()
    }

    @Test
    fun `should reject size without lookahead capacity and excessive sort`() {
        assertThrows<IllegalArgumentException> { CursorQuery(MatchAllFilter, size = 0) }
        assertThrows<IllegalArgumentException> { CursorQuery(MatchAllFilter, size = Int.MAX_VALUE) }
        assertThrows<IllegalArgumentException> {
            CursorQuery(
                MatchAllFilter,
                sort = List(AggregationQuery.MAX_SORT_FIELDS + 1) { Sort("field$it", Sort.Direction.ASC) },
            )
        }
    }

    @Test
    fun `should preserve cursor while rewriting filter and projection`() {
        val query = CursorQuery(MatchAllFilter, size = 20, cursor = "next")
            .withFilter(IdFilter("id"))
            .withProjection(Projection(include = listOf("state.name")))
        query.filter.assert().isEqualTo(IdFilter("id"))
        query.projection.include.assert().containsExactly("state.name")
        query.size.assert().isEqualTo(20)
        query.cursor.assert().isEqualTo("next")
    }
}
```

`CursorPageTest.kt` 使用当前 `JsonSerializer` 做 generic-free wire round-trip，并断言 `nextCursor` 可空：

```kotlin
@Test
fun `should round trip cursor page`() {
    val json = JsonSerializer.writeValueAsString(CursorPage(listOf("one"), "next"))
    val page = JsonSerializer.readValue(json, CursorPage::class.java)
    page.list.assert().containsExactly("one")
    page.nextCursor.assert().isEqualTo("next")
}
```

- [ ] **Step 2: 运行 API 测试确认 RED**

Run:

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.CursorQueryTest" --tests "me.ahoo.wow.api.query.CursorPageTest" --stacktrace
```

Expected: Kotlin 编译失败，`CursorQuery`/`CursorPage` 尚不存在。

- [ ] **Step 3: 实现最小公共契约**

`CursorQuery.kt` 的最终结构：

```kotlin
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class CursorQuery(
    @get:JsonIgnore(false)
    override val filter: FilterExpression,
    override val projection: Projection = Projection.ALL,
    @get:ArraySchema(maxItems = AggregationQuery.MAX_SORT_FIELDS)
    override val sort: List<Sort> = emptyList(),
    @get:Schema(defaultValue = "10", minimum = "1", maximum = "2147483646")
    override val size: Int = DEFAULT_SIZE,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    override val cursor: String? = null,
) : ICursorQuery {
    init {
        require(size in 1 until Int.MAX_VALUE) {
            "size must be between 1 and ${Int.MAX_VALUE - 1}."
        }
        require(sort.size <= AggregationQuery.MAX_SORT_FIELDS) {
            "sort must contain at most ${AggregationQuery.MAX_SORT_FIELDS} fields."
        }
    }

    override fun withFilter(newFilter: FilterExpression): ICursorQuery = copy(filter = newFilter)
    override fun withProjection(newProjection: Projection): ICursorQuery = copy(projection = newProjection)

    companion object {
        const val DEFAULT_SIZE: Int = 10
    }
}

interface ICursorQuery : Queryable<ICursorQuery> {
    val size: Int
    val cursor: String?
}
```

`CursorPage.kt`：

```kotlin
data class CursorPage<out T>(
    val list: List<T>,
    val nextCursor: String?,
)
```

`QueryProtocol.QueryEnvelope` 增加 `SIZE = "size"` 与 `CURSOR = "cursor"`。不增加 legacy `Condition` 构造器；CursorQuery 只接受 canonical filter。

- [ ] **Step 4: 写并验证 raw JSON Schema**

`schema/query/v2/cursor-query.schema.json`：

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/cursor-query.schema.json",
  "title": "Cursor Query",
  "type": "object",
  "properties": {
    "filter": { "$ref": "filter-expression.schema.json" },
    "projection": { "$ref": "../definitions.schema.json#/definitions/projection" },
    "sort": {
      "type": "array",
      "items": { "$ref": "../definitions.schema.json#/definitions/sort" },
      "maxItems": 32,
      "default": []
    },
    "size": { "type": "integer", "minimum": 1, "maximum": 2147483646, "default": 10 },
    "cursor": { "type": ["string", "null"], "default": null }
  },
  "required": ["filter"],
  "additionalProperties": false
}
```

`CursorQuerySchemaTest` 读取 `../schema/query/v2/cursor-query.schema.json`，断言 `filter` required、`additionalProperties=false`、size min/max/default、sort `maxItems=32`。

Run:

```bash
./gradlew :wow-api:check :wow-schema:test --tests "me.ahoo.wow.schema.query.CursorQuerySchemaTest" --stacktrace
```

Expected: PASS。

- [ ] **Step 5: 提交公共契约**

```bash
git add wow-api schema/query/v2/cursor-query.schema.json wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/CursorQuerySchemaTest.kt
git diff --cached --check
git commit -m "feat(query): add V9 cursor contracts"
```

---

### Task 2: 有效排序、静态 Mask 拒绝与 V9 Gateway

**Files:**
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/CursorQueries.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryBackend.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryGateway.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryType.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryContext.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaProviderResolution.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/CursorQueryDsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/Dsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/QueryDsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/QueryDsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotStates.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/CursorQueriesTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/CursorQueryDslTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewayApiTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewayContractTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewaySubscriptionTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/QueryContextTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/QueryTypeTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationModeTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/DefaultSnapshotQueryGatewayTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/DefaultEventStreamQueryGatewayTest.kt`

**Interfaces:**
- Consumes: `ICursorQuery`, `CursorQuery`, `CursorPage<T>` from Task 1。
- Produces: `ICursorQuery.withUniqueSort`, `QueryBackend.cursor`, typed/dynamic `QueryGateway.cursor`, `QueryType.CURSOR`, `QuerySchemaResolver.resolve(ICursorQuery)`。

- [ ] **Step 1: 写失败的 effective sort 测试**

```kotlin
class CursorQueriesTest {
    @Test
    fun `should append unique sort once`() {
        CursorQuery(MatchAllFilter, sort = listOf(Sort("version", Sort.Direction.DESC)))
            .withUniqueSort("aggregateId").sort.assert().containsExactly(
                Sort("version", Sort.Direction.DESC),
                Sort("aggregateId", Sort.Direction.ASC),
            )
    }

    @Test
    fun `should reject duplicate unstable and overflowing sort`() {
        assertThrows<IllegalArgumentException> {
            CursorQuery(
                MatchAllFilter,
                sort = listOf(Sort("id", Sort.Direction.ASC), Sort("id", Sort.Direction.DESC)),
            ).withUniqueSort("aggregateId")
        }
        listOf("_score", "_doc", "_shard_doc").forEach { field ->
            assertThrows<IllegalArgumentException> {
                CursorQuery(MatchAllFilter, sort = listOf(Sort(field, Sort.Direction.ASC)))
                    .withUniqueSort("aggregateId")
            }
        }
        assertThrows<IllegalArgumentException> {
            CursorQuery(
                MatchAllFilter,
                sort = List(AggregationQuery.MAX_SORT_FIELDS) { Sort("field$it", Sort.Direction.ASC) },
            ).withUniqueSort("aggregateId")
        }
    }
}
```

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.CursorQueriesTest" --stacktrace
```

Expected: 编译失败，`withUniqueSort` 不存在。

- [ ] **Step 2: 实现 effective sort helper**

```kotlin
private val FORBIDDEN_CURSOR_SORTS = setOf("_score", "_doc", "_shard_doc")

fun ICursorQuery.withUniqueSort(uniqueField: String): ICursorQuery {
    val effective = if (sort.any { it.field == uniqueField }) {
        sort
    } else {
        sort + Sort(uniqueField, Sort.Direction.ASC)
    }
    val fields = effective.map(Sort::field)
    require(fields.distinct().size == fields.size) { "Cursor sort fields must be unique." }
    require(fields.none(FORBIDDEN_CURSOR_SORTS::contains)) {
        "Cursor sort contains an unstable metadata field."
    }
    require(effective.size <= AggregationQuery.MAX_SORT_FIELDS) {
        "Effective cursor sort must contain at most ${AggregationQuery.MAX_SORT_FIELDS} fields."
    }
    return CursorQuery(filter, projection, effective, size, cursor)
}
```

- [ ] **Step 3: 写失败的 cursor Schema 安全测试**

在 `QuerySchemaResolverTest` 使用现有 `schema`、`fieldSchema`、`fullMaskRule` fixtures：

```kotlin
@Test
fun `cursor sort should require exact unmasked bindings while ordinary sort remains allowed`() {
    val secret = LogicalField("state.secret")
    val resolver = QuerySchemaResolver(
        schema(
            mapOf(
                secret to fieldSchema(
                    QueryCapability.SORT to "document.secret.keyword",
                    maskRule = fullMaskRule(),
                ),
            ),
        ),
    )

    resolver.resolve(ListQuery(MatchAllFilter, sort = listOf(Sort(secret.value))))
        .compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
    resolver.resolve(CursorQuery(MatchAllFilter, sort = listOf(Sort(secret.value))))
        .compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    resolver.resolve(CursorQuery(MatchAllFilter, sort = listOf(Sort("state.unknown", Sort.Direction.ASC))))
        .compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
}
```

同一测试再覆盖动态 masked 祖先：

```kotlin
val dynamic = LogicalField("state.dynamic")
val dynamicResolver = QuerySchemaResolver(
    schema(
        mapOf(
            dynamic to fieldSchema(
                QueryCapability.SORT to "document.dynamic",
                dynamicChildren = true,
                maskRule = fullMaskRule(),
            ),
        ),
    ),
)
dynamicResolver.resolve(
    CursorQuery(
        MatchAllFilter,
        sort = listOf(Sort("state.dynamic.secret", Sort.Direction.ASC)),
    ),
).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
```

在 `QuerySchemaValidationModeTest` 添加 Schema unavailable fail-closed：

```kotlin
@Test
fun `compatible cursor should not fallback when schema is unavailable`() {
    val provider = object : QueryModelSchemaProvider {
        override fun schema(): Mono<QueryModelSchema> = Mono.error(QuerySchemaUnavailableException("missing"))
        override fun refresh(): Mono<QueryModelSchema> = schema()
    }
    provider.resolve(
        CursorQuery(MatchAllFilter, sort = listOf(Sort("aggregateId", Sort.Direction.ASC))),
        COMPATIBLE,
    )
        .test().expectError(QuerySchemaUnavailableException::class.java).verify()
}
```

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaResolverTest" --tests "me.ahoo.wow.query.schema.QuerySchemaValidationModeTest" --stacktrace
```

Expected: `resolve(ICursorQuery)` 不存在或 masked sort 仍被接受。

- [ ] **Step 4: 实现 cursor 专用 Schema resolution**

在 `QuerySchemaResolver` 增加独立 cursor 分支，不改变现有 `resolve(List<Sort>)`：

```kotlin
fun resolve(query: ICursorQuery): QuerySchemaResolution<ICursorQuery> {
    val filter = resolve(query.filter)
    val projection = resolve(query.projection)
    val sort = resolveCursorSort(query.sort)
    return QuerySchemaResolution(
        CursorQuery(filter.value, projection.value, sort.value, query.size, query.cursor),
        listOf(filter.compatibility, projection.compatibility, sort.compatibility).combined(),
    )
}

private fun resolveCursorSort(sort: List<Sort>): QuerySchemaResolution<List<Sort>> {
    val resolved = sort.map { item ->
        val field = runCatching { LogicalField(item.field) }.getOrNull()?.let { logical ->
            fieldResolver.resolve(logical, QueryCapability.SORT, null, null)
        }
        val accepted = field?.compatibility == QueryCompatibilityLevel.EXACT &&
            field.fieldSchema?.cardinality == QueryCardinality.SINGLE &&
            field.fieldSchema.maskRule == null && !field.matchesMaskedCandidate()
        item.copy(field = field?.value ?: item.field) to
            if (accepted) QueryCompatibilityLevel.EXACT else QueryCompatibilityLevel.INCOMPATIBLE
    }
    return QuerySchemaResolution(
        resolved.map { it.first },
        resolved.map { it.second }.combined(),
    )
}
```

`QueryModelSchemaProviderResolution.kt` 直接加载 Schema，无 `fallbackUnavailable`：

```kotlin
fun QueryModelSchemaProvider.resolve(
    query: ICursorQuery,
    mode: QuerySchemaValidationMode,
): Mono<ICursorQuery> = schema().map { QuerySchemaResolver(it).resolve(query).requireAccepted(mode) }
```

- [ ] **Step 5: 写失败的 Gateway dispatch/mask/subscription 测试**

`QueryGatewayApiTest` 反射断言 typed/dynamic cursor 返回 `Mono`。`QueryGatewaySubscriptionTest` 将 cursor 加入重复订阅操作集合。

在 `DefaultSnapshotQueryGatewayTest` 使用现有 masked schema fixture：

```kotlin
@Test
fun `cursor should mask raw page and preserve next cursor`() {
    val backend = SchemaSnapshotBackend(Mono.just(maskedSchema()))
    val gateway = gateway(backend)

    gateway.dynamicCursor(
        CursorQuery(MatchAllFilter, sort = listOf(Sort("aggregateId", Sort.Direction.ASC))),
    )
        .test()
        .assertNext { page ->
            page.nextCursor.assert().isEqualTo("next")
            page.list.single().path("state").path("value").textValue().assert().isNotEqualTo("secret")
        }.verifyComplete()
}
```

同时在该测试 fixture 中增加确定性的 cursor publisher：

```kotlin
override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> = Mono.fromSupplier {
    resultSubscriptions.incrementAndGet()
    CursorPage(listOf(nodeSupplier()), "next")
}
```

Event gateway 增加对应 event-body masked cursor case；`QueryGatewayContractTest` 的 raw Backend objects 覆盖 cursor 返回一页，断言 typed materialization 发生在 mask 之后。

同时用未覆盖 cursor 的最小 `SnapshotQueryBackend` 断言默认能力：

```kotlin
unsupportedBackend.cursor(CursorQuery(MatchAllFilter))
    .test().expectErrorMessage("Cursor query is not supported.").verify()
```

现有 Gateway error-handler 参数化测试加入 CURSOR，断言 Backend failure 仍先交给 ErrorHandler，再把原异常返回调用方。

- [ ] **Step 6: 扩展 V9 Backend/Gateway/Context**

`QueryBackend` 默认能力：

```kotlin
fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> =
    Mono.error(UnsupportedOperationException("Cursor query is not supported."))
```

`QueryGateway<R>` 增加同错误合同的 `cursor`/`dynamicCursor`，`AbstractQueryGateway` 覆盖实现。`QueryType` 只新增 `CURSOR`；`QueryContext` 增加：

```kotlin
fun asCursorQuery(): QueryContext<ICursorQuery, Mono<CursorPage<ObjectNode>>> =
    this as QueryContext<ICursorQuery, Mono<CursorPage<ObjectNode>>>
```

`QueryTypeTest` 的精确枚举顺序更新为 `SINGLE, LIST, PAGED, CURSOR, COUNT, AGGREGATION`；
`QueryContextTest` 直接断言 `asCursorQuery()` 读写 `Mono<CursorPage<ObjectNode>>`。

`invokeBackend` 分支：

```kotlin
QueryType.CURSOR -> context.asCursorQuery().setResult { backend.cursor(it) }
```

Gateway mask helper与公开方法：

```kotlin
private fun Mono<CursorPage<ObjectNode>>.maskCursorResult(): Mono<CursorPage<ObjectNode>> =
    masker?.flatMap { optional ->
        optional.map { schemaMasker ->
            map { page -> page.copy(list = page.list.map(schemaMasker::mask)) }
        }.orElse(this)
    } ?: this

override fun cursor(query: ICursorQuery): Mono<CursorPage<R>> =
    mono<ICursorQuery, Mono<CursorPage<ObjectNode>>, CursorPage<R>>(QueryType.CURSOR, query) { context ->
        context.getRequiredResult().maskCursorResult().map { page ->
            CursorPage(page.list.map { it.toObject<R>(targetType) }, page.nextCursor)
        }
    }

override fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> =
    mono<ICursorQuery, Mono<CursorPage<ObjectNode>>, CursorPage<ObjectNode>>(QueryType.CURSOR, query) {
        it.getRequiredResult().maskCursorResult()
    }
```

- [ ] **Step 7: 接入 DSL 与 state-only conversion**

`CursorQueryDsl`：

```kotlin
@QueryDslMarker
class CursorQueryDsl : QueryableDsl<ICursorQuery>() {
    private var size: Int = CursorQuery.DEFAULT_SIZE
    private var cursor: String? = null
    fun size(value: Int) { size = value }
    fun cursor(value: String?) { cursor = value }
    override fun build(): ICursorQuery = CursorQuery(filter, projection, sort, size, cursor)
}
```

`Dsl.kt` 增加 `cursorQuery {}`；Snapshot/EventStream `QueryDsl.kt` 增加 typed/dynamic extension。`SnapshotStates.kt` 增加：

```kotlin
fun <S : Any> Mono<CursorPage<MaterializedSnapshot<S>>>.toStateCursorPage(): Mono<CursorPage<S>> =
    map { page -> CursorPage(page.list.map { it.state }, page.nextCursor) }

fun Mono<CursorPage<ObjectNode>>.toStateDocumentCursorPage(): Mono<CursorPage<ObjectNode>> =
    map { page -> page.copy(list = page.list.map(ObjectNode::toState)) }
```

- [ ] **Step 8: 运行 Query 模块全检查并提交**

```bash
./gradlew :wow-query:check --stacktrace
git diff --check
git add wow-query
git commit -m "feat(query): add V9 cursor gateway pipeline"
```

Expected: Query check PASS；普通 sort masked test 仍通过；cursor masked/unknown/unavailable 均 fail-closed；不存在 `CursorTokenCodec`。

---

### Task 3: MongoDB BSON cursor 与 keyset Backend

**Files:**
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCursorFilterCompiler.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCursorDocuments.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoProjectionConverter.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackend.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoCursorFilterCompilerTest.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoCursorDocumentsTest.kt`
- Modify: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackendTest.kt`

**Interfaces:**
- Consumes: `QueryBackend.cursor`, `ICursorQuery.withUniqueSort`, `CursorPage<ObjectNode>`。
- Produces: 内置 Mongo Snapshot/EventStream cursor Backend；BSON Base64URL token。

- [ ] **Step 1: 写失败的词典序与 BSON token 测试**

```kotlin
@Test
fun `should compile mixed direction lexicographic cursor`() {
    MongoCursorFilterCompiler.compile(
        listOf(Sort("createdAt", Sort.Direction.DESC), Sort("_id", Sort.Direction.ASC)),
        listOf(100, "id-1"),
    ).toBsonDocument().toJson().assert().contains(
        "\"createdAt\": {\"\$lt\": 100}",
        "\"createdAt\": 100",
        "\"_id\": {\"\$gt\": \"id-1\"}",
    )
}

@Test
fun `BSON cursor should round trip native scalars without a key`() {
    val values = listOf(null, "x", true, 1, 2L, 1.5, Date(1), BsonTimestamp(2, 3), Decimal128(4))
    MongoCursorCodec.decode(MongoCursorCodec.encode(values), values.size).assert().isEqualTo(values)
}

@Test
fun `BSON cursor should reject malformed arity and object values`() {
    assertThrows<IllegalArgumentException> { MongoCursorCodec.decode("not-base64", 1) }
    assertThrows<IllegalArgumentException> { MongoCursorCodec.decode(MongoCursorCodec.encode(listOf(1)), 2) }
    assertThrows<IllegalArgumentException> { MongoCursorCodec.encode(listOf(Document("nested", 1))) }
}
```

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.MongoCursorFilterCompilerTest" --tests "me.ahoo.wow.mongo.query.MongoCursorDocumentsTest" --stacktrace
```

Expected: 新 compiler/codec 尚不存在。

- [ ] **Step 2: 实现 BSON Base64URL 与 keyset compiler**

`MongoCursorCodec` 不接收 key：

```kotlin
internal object MongoCursorCodec {
    private const val VALUES = "values"
    private val documentCodec = DocumentCodec()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(values: List<Any?>): String = invalidCursor {
        require(values.size <= AggregationQuery.MAX_SORT_FIELDS && values.all(Any?::isMongoCursorScalar))
        val raw = RawBsonDocument(Document(VALUES, values), documentCodec)
        encoder.encodeToString(raw.backingArray.copyOfRange(raw.byteOffset, raw.byteOffset + raw.byteLength))
    }

    fun decode(cursor: String, expectedSize: Int): List<Any?> = invalidCursor {
        require(expectedSize in 1..AggregationQuery.MAX_SORT_FIELDS)
        val document = RawBsonDocument(decoder.decode(cursor)).decode(documentCodec)
        require(document.keys == setOf(VALUES))
        val values = document[VALUES] as? List<*> ?: throw IllegalArgumentException()
        require(values.size == expectedSize && values.all(Any?::isMongoCursorScalar))
        values.toList()
    }

    private inline fun <T> invalidCursor(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid cursor.")
    }
}
```

`MongoCursorFilterCompiler.compile` 复用已验证的 null/missing 语义：ASC null 使用 `ne(field, null)`；DESC null 只允许后续 tie-breaker；非 null ASC/ DESC 分别用 `gt` / `lt OR null`。

- [ ] **Step 3: 写失败的 query-shape 与 projection 测试**

`AbstractMongoQueryBackendTest`：

```kotlin
@Test
fun `cursor should use lookahead without count or skip`() {
    cursorPublisher(listOf(Document("rank", 1), Document("rank", 2)), limit = 2)
    backend.cursor(
        CursorQuery(MatchAllFilter, sort = listOf(Sort("rank", Sort.Direction.ASC)), size = 1),
    ).block()
    verify(exactly = 1) { publisher.limit(2) }
    verify(exactly = 0) { publisher.skip(any()) }
    verify(exactly = 0) { collection.countDocuments(any<Bson>()) }
}
```

`MongoCursorDocumentsTest` 覆盖：

```kotlin
@Test
fun `included projection should remove cursor-only empty parents`() {
    val projection = Projection(include = listOf("name")).withCursorFields(listOf("state.createdAt"))
    val page = listOf(
        Document("name", "one").append("state", Document("createdAt", 1)),
        Document("name", "two").append("state", Document("createdAt", 2)),
    ).toCursorPage(cursorQuery(size = 1), projection) { it }
    page.list.single().containsKey("state").assert().isFalse()
    MongoCursorCodec.decode(page.nextCursor!!, 1).single().assert().isEqualTo(1)
}
```

同时覆盖用户已包含 sort 字段时保留、exclude parent + sort child、missing 视为 null、token 来自最后返回记录而非 lookahead。

- [ ] **Step 4: 实现 Mongo cursor 查询流**

`AbstractMongoQueryBackend` 增加：

```kotlin
protected open val cursorUniqueField: String? = null
protected open fun resolve(query: ICursorQuery): Mono<ICursorQuery> = Mono.just(query)

override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> {
    val uniqueField = cursorUniqueField
        ?: return Mono.error(UnsupportedOperationException("Cursor query is not supported."))
    return resolve(query.withUniqueSort(uniqueField)).flatMap { resolved ->
        val cursorFilter = resolved.cursor?.let {
            MongoCursorFilterCompiler.compile(resolved.sort, MongoCursorCodec.decode(it, resolved.sort.size))
        }
        val filter = cursorFilter?.let { Filters.and(converter.convert(resolved.filter), it) }
            ?: converter.convert(resolved.filter)
        val projection = resolved.projection.withCursorFields(resolved.sort.map(Sort::field))
        collection.find(filter)
            .projection(projectionConverter.convertCursor(projection))
            .sort(sortConverter.convert(resolved.sort))
            .limit(resolved.size + 1)
            .toFlux().collectList()
            .map { it.toCursorPage(resolved, projection, mapper = ::toObjectNode) }
    }
}
```

`MongoProjectionConverter` 只增加 `cursorProjection`/`convertCursor` 的窄入口。`MongoCursorDocuments.kt` 从物理 sort path 读取值，生成 token 后删除临时字段；包含型 projection 递归删除空父 Document，排除型保持原语义。

- [ ] **Step 5: 内置 Backend 接入唯一键与 fail-closed Schema**

Snapshot：

```kotlin
override val cursorUniqueField: String = MessageRecords.AGGREGATE_ID
override fun resolve(query: ICursorQuery) = schemaProvider.resolve(query, validationMode)
```

EventStream 使用 `MessageRecords.ID`。必须先 `withUniqueSort` 再 resolve，使唯一字段也走 EXACT + unmasked 校验。

- [ ] **Step 6: 运行 Mongo check 并提交**

```bash
./gradlew :wow-mongo:check --stacktrace
git diff --check
git add wow-mongo
git commit -m "feat(mongo): add V9 cursor keyset backend"
```

Expected: PASS；cursor path 无 count/skip；源码不存在 AES 或 key 参数。

---

### Task 4: Elasticsearch Base64URL search_after Backend

**Files:**
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchCursorCodec.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryBackend.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchCursorCodecTest.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackendTest.kt`

**Interfaces:**
- Consumes: Task 2 的 Backend/Gateway contract 与 effective sort。
- Produces: 内置 Elasticsearch Snapshot/EventStream cursor Backend；JSON Base64URL token。

- [ ] **Step 1: 写失败的 FieldValue codec 测试**

```kotlin
@Test
fun `cursor codec should round trip scalar field values without a key`() {
    val values = listOf(FieldValue.NULL, FieldValue.of(true), FieldValue.of("x"), FieldValue.of(1L), FieldValue.of(1.5))
    ElasticsearchCursorCodec.decode(ElasticsearchCursorCodec.encode(values), values.size)
        .assert().isEqualTo(values)
}

@Test
fun `cursor codec should reject malformed arity and non scalar values`() {
    assertThrows<IllegalArgumentException> { ElasticsearchCursorCodec.decode("not-base64", 1) }
    assertThrows<IllegalArgumentException> {
        ElasticsearchCursorCodec.decode(ElasticsearchCursorCodec.encode(listOf(FieldValue.of(1))), 2)
    }
    val objectCursor = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""[{"nested":1}]""".toByteArray())
    assertThrows<IllegalArgumentException> {
        ElasticsearchCursorCodec.decode(objectCursor, 1)
    }
}
```

Run:

```bash
./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.ElasticsearchCursorCodecTest" --stacktrace
```

Expected: `ElasticsearchCursorCodec` 不存在。

- [ ] **Step 2: 实现 JSON Base64URL codec**

```kotlin
internal object ElasticsearchCursorCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(values: List<FieldValue>): String = invalidCursor {
        require(values.size <= AggregationQuery.MAX_SORT_FIELDS)
        encoder.encodeToString(JsonSerializer.writeValueAsBytes(values.map(FieldValue::toCursorValue)))
    }

    fun decode(cursor: String, expectedSize: Int): List<FieldValue> = invalidCursor {
        require(expectedSize in 1..AggregationQuery.MAX_SORT_FIELDS)
        val values = JsonSerializer.readTree(decoder.decode(cursor))
        require(values.isArray && values.size() == expectedSize)
        values.map(JsonNode::toFieldValue)
    }
}
```

`JsonNode.toFieldValue` 与 `FieldValue.toCursorValue` 只接受 null/boolean/string/long/double；全部错误统一为 `IllegalArgumentException("Invalid cursor.")`。

- [ ] **Step 3: 写失败的 request-shape 测试**

```kotlin
@Test
fun `cursor should use search after without total from or pit`() {
    val request = slot<SearchRequest>()
    every { client.search(capture(request), ObjectNode::class.java) } returns Mono.just(
        searchResponseWithSortValues(1L to "id-1", 2L to "id-2"),
    )

    val page = backend.cursor(
        CursorQuery(MatchAllFilter, sort = listOf(Sort("version", Sort.Direction.ASC)), size = 1),
    ).block()!!

    request.captured.size().assert().isEqualTo(2)
    request.captured.from().assert().isNull()
    request.captured.trackTotalHits()!!.enabled().assert().isFalse()
    request.captured.pit().assert().isNull()
    page.list.assert().hasSize(1)
    page.nextCursor.assert().isNotNull()
    verify(exactly = 0) { client.openPointInTime(any<OpenPointInTimeRequest>()) }
}
```

第二次请求断言 `searchAfter()` 等于第一页最后返回 hit 的 sort values；末页 `nextCursor == null`；hit sort arity 缺失统一失败。

- [ ] **Step 4: 实现独立 cursor SearchRequest**

`AbstractElasticsearchQueryBackend` 增加 nullable `cursorUniqueField` 与 `resolve(ICursorQuery)` hook。cursor request 不复用会设置 `from` 的 paged builder：

```kotlin
override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> {
    val uniqueField = cursorUniqueField
        ?: return Mono.error(UnsupportedOperationException("Cursor query is not supported."))
    return resolve(query.withUniqueSort(uniqueField)).flatMap { resolved ->
        val compiled = compile(resolved.filter, resolved.sort)
        elasticsearchClient.search(cursorSearchRequest(resolved, compiled), ObjectNode::class.java)
            .map { response -> response.toCursorPage(resolved) }
    }
}

private fun cursorSearchRequest(query: ICursorQuery, resolved: ResolvedQuery): SearchRequest = SearchRequest.of {
    it.index(indexName)
        .query(resolved.query)
        .size(query.size + 1)
        .sort(resolved.sortOptions.withCursorMissing(query.sort))
        .trackTotalHits { hits -> hits.enabled(false) }
    query.cursor?.let { token -> ElasticsearchCursorCodec.decode(token, query.sort.size) }
        ?.let(it::searchAfter)
    if (!query.projection.isEmpty()) it.source { source -> source.filter(query.projection.toSourceFilter()) }
    it
}
```

响应只取前 `size` 个 hit；存在 lookahead 时用最后一个已返回 hit 的 `sort()` 生成 token。ASC missing 设 `_first`，DESC missing 设 `_last`；不追加 `_shard_doc`。

- [ ] **Step 5: 内置 Backend 接入 Schema resolve**

Snapshot 使用 `MessageRecords.AGGREGATE_ID`，EventStream 使用 `MessageRecords.ID`；两者覆盖 `resolve(ICursorQuery) = schemaProvider.resolve(query, validationMode)`。

- [ ] **Step 6: 运行 Elasticsearch check 并提交**

```bash
./gradlew :wow-elasticsearch:check --stacktrace
git diff --check
git add wow-elasticsearch
git commit -m "feat(elasticsearch): add V9 cursor search-after backend"
```

Expected: PASS；ListQuery 的 PIT pager 与 PagedQuery 的精确 total 行为未改变。

---

### Task 5: MongoDB/Elasticsearch 通用 Backend TCK

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryBackendSpec.kt`
- Modify only if a TCK proves a backend defect: Task 3/4 production files

**Interfaces:**
- Consumes: 两个内置 Backend cursor 实现。
- Produces: 跨后端一致的多页、唯一键、DESC、null/missing 与 projection 证据。

- [ ] **Step 1: 写 Snapshot 真实多页 TCK**

保存三个 version 相同、aggregateId 不同的 snapshot，然后：

```kotlin
val query = CursorQuery(
    MatchAllFilter,
    sort = listOf(Sort("version", Sort.Direction.ASC)),
    size = 2,
)
val first = snapshotQueryBackend.cursor(query).block()!!
val second = snapshotQueryBackend.cursor(query.copy(cursor = first.nextCursor)).block()!!

first.list.assert().hasSize(2)
first.nextCursor.assert().isNotNull()
(first.list + second.list).map { it.path("aggregateId").textValue() }.distinct().assert().hasSize(3)
second.nextCursor.assert().isNull()
```

增加 DESC、多字段、missing/null、include projection 排除内部 sort 字段用例。

无匹配用例必须明确断言：

```kotlin
snapshotQueryBackend.cursor(
    CursorQuery(IdFilter("missing"), sort = listOf(Sort("aggregateId", Sort.Direction.ASC))),
).test().expectNext(CursorPage(emptyList(), null)).verifyComplete()
```

- [ ] **Step 2: 写 EventStream 真实多页 TCK**

追加三个 event stream，按 `version + id` 遍历：

```kotlin
val query = CursorQuery(
    MatchAllFilter,
    sort = listOf(Sort("version", Sort.Direction.ASC)),
    size = 2,
)
val first = eventStreamQueryBackend.cursor(query).block()!!
val second = eventStreamQueryBackend.cursor(query.copy(cursor = first.nextCursor)).block()!!

first.list.assert().hasSize(2)
(first.list + second.list).map { it.path("id").textValue() }.distinct().assert().hasSize(3)
first.nextCursor.assert().isNotNull()
second.nextCursor.assert().isNull()
```

增加 projection 不泄漏临时 sort 字段与 malformed cursor 失败用例。
另用不存在的 tenantId 断言 `CursorPage(emptyList(), null)`。

- [ ] **Step 3: 运行两个真实 Backend TCK**

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

Expected: 新增 Snapshot/EventStream cursor TCK 在 MongoDB 与 Elasticsearch 均 PASS。若失败，先按 systematic-debugging 定位，再只修正 Task 3/4 的 compiler、codec、projection 或 request builder；不得删除测试、增加 PIT/状态或放宽 Mask 拒绝。

- [ ] **Step 4: 修正后重跑并提交 TCK**

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
git diff --check
git add test/wow-tck wow-mongo wow-elasticsearch
git commit -m "test(query): verify cursor backends"
```

Expected: 两个真实后端 integration tests PASS。

---

### Task 6: WebFlux 路由、HTTP Guard 与 Starter 注册

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/CursorQueryHandlerFunction.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/CursorQuerySnapshotHandlerFunction.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/CursorQuerySnapshotStateHandlerFunction.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/CursorQueryEventStreamHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/QueryBodyExtractor.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilter.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/BuiltInHttpRoutes.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/QueryRouteModule.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/QueryBodyExtractorTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilterTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`

**Interfaces:**
- Consumes: `QueryGateway.dynamicCursor`, `CursorPage<ObjectNode>`, `toStateDocumentCursorPage`。
- Produces: `/snapshot/cursor`、`/snapshot/cursor/state`、`/event/cursor` runtime handlers。

- [ ] **Step 1: 写失败的 body 与 guard 测试**

`QueryBodyExtractorTest` 通过现有 extractor helper 断言 canonical cursor body 成功，unknown property、explicit null filter、size 0/Int.MAX_VALUE 失败。

`HttpQueryGuardFilterTest`：

```kotlin
@Test
fun `cursor should use max page size without page window or counting rejection`() {
    val filter = HttpQueryGuardFilter(maxPageSize = 2, maxPageWindow = 1, allowExpensiveOperators = false)
    filter.filter(cursorContext(CursorQuery(MatchAllFilter, size = 2)), EmptyFilterChain.instance())
        .contextWrite(Context.of(RAW_REQUEST_KEY, request))
        .test().verifyComplete()
}

@Test
fun `cursor should reject oversized pages and expensive filters`() {
    guard(maxPageSize = 2).validateForTest(CursorQuery(MatchAllFilter, size = 3))
        .test().expectError(IllegalArgumentException::class.java).verify()
    guard(allowExpensiveOperators = false).validateForTest(
        CursorQuery(ContainsFilter(LogicalField("state.name"), "x")),
    ).test().expectError(IllegalArgumentException::class.java).verify()
}
```

使用本地 `cursorContext` 构造 `DefaultQueryContext<ICursorQuery, Mono<CursorPage<ObjectNode>>>`，不暴露生产 validate 方法。

```kotlin
private fun cursorContext(query: ICursorQuery) =
    DefaultQueryContext<ICursorQuery, Mono<CursorPage<ObjectNode>>>(
        QueryType.CURSOR,
        MOCK_AGGREGATE_METADATA,
    ).setQuery(query).setResult(Mono.just(CursorPage(emptyList(), null)))

private fun HttpQueryGuardFilter.validateForTest(query: ICursorQuery): Mono<Void> =
    filter(cursorContext(query), EmptyFilterChain.instance())
        .contextWrite(Context.of(RAW_REQUEST_KEY, request))
```

- [ ] **Step 2: 实现 body extractor、guard 与 handler keys**

`QueryBodyExtractor` 增加：

```kotlin
val CURSOR_QUERY_EXTRACTOR = QueryBodyExtractor(CursorQuery::class.java)
```

`HttpQueryGuardFilter.validate` 在 paged 前处理 `ICursorQuery.size`；`CURSOR` 不加入 `COUNTING_QUERY_TYPES`，但继续验证 filter nodes/values/expensive operators。idle timeout 增加：

```kotlin
QueryType.CURSOR -> context.asCursorQuery().rewriteResult { it.timeout(idleTimeout) }
```

`BuiltInHttpRouteHandlerKeys` 增加 Snapshot `CURSOR_QUERY`/`CURSOR_QUERY_STATE` 和 Event `CURSOR_QUERY`。

- [ ] **Step 3: 实现通用 CursorQuery handler**

```kotlin
class CursorQueryHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val queryGateway: QueryGateway<*>,
    private val rewriteRequestFilter: RewriteRequestFilter,
    private val exceptionHandler: RequestExceptionHandler,
    private val rewriteResult: (Mono<CursorPage<ObjectNode>>) -> Mono<CursorPage<ObjectNode>> = { it },
) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> =
        request.body(QueryBodyExtractor.CURSOR_QUERY_EXTRACTOR)
            .flatMap { body ->
                val query = rewriteRequestFilter.rewrite(aggregateMetadata, request, body)
                rewriteResult(queryGateway.dynamicCursor(query)).writeRawRequest(request)
            }.toServerResponse(request, exceptionHandler)
}
```

Factory 接收 `(AggregateMetadata<*, *>) -> QueryGateway<*>`，与当前 Paged factory 保持一致。Snapshot state factory 应用 `toStateDocumentCursorPage`；Event factory不改写。

- [ ] **Step 4: Starter 注册三个 factory 并验证路由**

`QueryRouteModule.httpFactories` 在对应 paged factory 后加入三项。`WebFluxAutoConfigurationTest` 断言 handler key 唯一且三个 factory 均存在。

Run:

```bash
./gradlew :wow-webflux:check :wow-spring-boot-starter:check --stacktrace
```

Expected: PASS；无 SSE cursor route；没有 QueryProperties、encryption key 或 Backend factory 构造器修改。

- [ ] **Step 5: 提交 HTTP runtime**

```bash
git diff --check
git add wow-webflux wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/BuiltInHttpRoutes.kt wow-spring-boot-starter
git commit -m "feat(webflux): expose V9 cursor routes"
```

---

### Task 7: OpenAPI 与 Snapshot API Client

**Files:**
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/QueryComponent.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/QueryContractComponentSupport.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/snapshot/SnapshotRouteContributor.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/event/EventRouteContributor.kt`
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiCompatibilitySnapshotTest.kt`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Create: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SnapshotCursorQueryApi.kt`
- Create: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/ReactiveSnapshotCursorQueryApi.kt`
- Create: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SynchronousSnapshotCursorQueryApi.kt`
- Create: `wow-apiclient/src/test/kotlin/me/ahoo/wow/apiclient/query/SnapshotCursorQueryApiTest.kt`

**Interfaces:**
- Consumes: Task 1 contracts与 Task 6 handler keys。
- Produces: 可发现 OpenAPI routes/components 与显式 opt-in Snapshot Cursor Client。

- [ ] **Step 1: 写失败的 API Client contract 测试**

```kotlin
@Test
fun `cursor methods should use cursor resources`() {
    val methods = SnapshotCursorQueryApi::class.java.methods.associateBy(Method::getName)
    methods.getValue("cursor").getAnnotation(PostExchange::class.java).value.assert()
        .isEqualTo("snapshot/cursor")
    methods.getValue("dynamicCursor").getAnnotation(PostExchange::class.java).value.assert()
        .isEqualTo("snapshot/cursor")
    methods.getValue("cursorState").getAnnotation(PostExchange::class.java).value.assert()
        .isEqualTo("snapshot/cursor/state")
}
```

再用匿名 `ReactiveSnapshotCursorQueryApi<State>` 编译验证返回类型分别是 `Mono<CursorPage<MaterializedSnapshot<State>>>`、`Mono<CursorPage<Map<String, Any>>>`、`Mono<CursorPage<State>>`。

- [ ] **Step 2: 实现显式 opt-in Snapshot Client**

```kotlin
const val SNAPSHOT_CURSOR_QUERY_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/cursor"
const val SNAPSHOT_CURSOR_QUERY_STATE_RESOURCE_NAME = "$SNAPSHOT_CURSOR_QUERY_RESOURCE_NAME/state"

interface SnapshotCursorQueryApi<R, RD, RS> : SnapshotQueryApi {
    @PostExchange(SNAPSHOT_CURSOR_QUERY_RESOURCE_NAME)
    fun cursor(@RequestBody query: ICursorQuery): R

    @PostExchange(SNAPSHOT_CURSOR_QUERY_RESOURCE_NAME)
    fun dynamicCursor(@RequestBody query: ICursorQuery): RD

    @PostExchange(SNAPSHOT_CURSOR_QUERY_STATE_RESOURCE_NAME)
    fun cursorState(@RequestBody query: ICursorQuery): RS
}
```

Reactive/Synchronous 接口只组合这三个返回类型；不修改 `ReactiveSnapshotQueryApi` 和 `SynchronousSnapshotQueryApi` 的父接口集合，不新增 EventStream client。

- [ ] **Step 3: 写失败的 OpenAPI route/schema 断言**

```kotlin
val paths = mapper.valueToTree<JsonNode>(openAPI).path("paths")
paths.fieldNames().asSequence().any { it.endsWith("/snapshot/cursor") }.assert().isTrue()
paths.fieldNames().asSequence().any { it.endsWith("/snapshot/cursor/state") }.assert().isTrue()
paths.fieldNames().asSequence().any { it.endsWith("/event/cursor") }.assert().isTrue()

val cursor = mapper.valueToTree<JsonNode>(openAPI)
    .path("components").path("schemas").path("wow.api.query.CursorQuery")
cursor.path("properties").has("size").assert().isTrue()
cursor.path("properties").has("cursor").assert().isTrue()
cursor.path("properties").has("pagination").assert().isFalse()
```

- [ ] **Step 4: 扩展 OpenAPI components 与 contributors**

`QueryComponent` 增加：

```kotlin
const val CURSOR_QUERY_SUFFIX = ".CursorQuery"
const val CURSOR_QUERY_KEY = Wow.WOW + CURSOR_QUERY_SUFFIX

fun OpenAPIComponentContext.cursorQuerySchema(): Schema<*> = schema(CursorQuery::class.java)

fun OpenAPIComponentContext.cursorQueryRequestBody(): RequestBody = requestBody(CURSOR_QUERY_KEY) {
    content(schema = cursorQuerySchema())
}

fun OpenAPIComponentContext.aggregatedCursorQueryRequestBody(
    aggregateMetadata: AggregateMetadata<*, *>,
): RequestBody = aggregatedQueryRequestBody(aggregateMetadata, CURSOR_QUERY_SUFFIX, cursorQuerySchema())
```

`QueryContractComponentSupport` 增加 `cursorQueryRequestBodyRef`、`aggregatedCursorQueryRequestBodyRef`，并使用
`schema(CursorPage::class.java, itemType)` 分别生成 `materializedSnapshotCursorResponse`、
`stateCursorResponse`、`eventStreamCursorResponse`。

Snapshot contributor 为现有 tenant/owner variants 增加：

```kotlin
snapshotRoute(
    handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY,
    operation = "cursor_query",
    appendPathSuffix = "snapshot/cursor",
    requestBody = componentContext.aggregatedCursorQueryRequestBodyRef(aggregateMetadata),
    responses = listOf(componentContext.materializedSnapshotCursorResponse(aggregateMetadata)),
)
```

state route 使用 `snapshot/cursor/state` 与 `stateCursorResponse`；Event contributor 使用 `event/cursor`、
`cursorQueryRequestBodyRef` 与 `eventStreamCursorResponse`。三者都只声明 JSON response，不声明 event-stream。

- [ ] **Step 5: 更新并复验 snapshots**

```bash
./gradlew :wow-openapi:test -Dwow.snapshot.update=true --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" --stacktrace
git diff -- wow-openapi/src/test/resources/openapi
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" --stacktrace
```

Expected: snapshot 只新增 CursorQuery/CursorPage components 与三组 route variants；旧 route/schema 不改变。

- [ ] **Step 6: 运行 client/openapi check 并提交**

```bash
./gradlew :wow-apiclient:check :wow-openapi:check --stacktrace
git diff --check
git add wow-apiclient wow-openapi
git commit -m "feat(query): publish V9 cursor contracts"
```

---

### Task 8: V9 中英文用户文档

**Files:**
- Modify: `documentation/docs/zh/guide/query/snapshot-query.md`
- Modify: `documentation/docs/en/guide/query/snapshot-query.md`
- Modify: `documentation/docs/zh/guide/query/event-stream-query.md`
- Modify: `documentation/docs/en/guide/query/event-stream-query.md`
- Modify: `documentation/docs/zh/guide/query/query-gateway.md`
- Modify: `documentation/docs/en/guide/query/query-gateway.md`
- Modify: `documentation/docs/zh/guide/query/query-backend.md`
- Modify: `documentation/docs/en/guide/query/query-backend.md`
- Modify: `documentation/docs/zh/guide/query/query-api-client.md`
- Modify: `documentation/docs/en/guide/query/query-api-client.md`
- Modify: `documentation/docs/zh/guide/query/masking.md`
- Modify: `documentation/docs/en/guide/query/masking.md`
- Modify: `documentation/docs/zh/guide/open-api.md`
- Modify: `documentation/docs/en/guide/open-api.md`

**Interfaces:**
- Consumes: 已验证 runtime、OpenAPI 与 client 契约。
- Produces: 与 V9 静态 Mask 和无密钥 token 一致的双语文档。

- [ ] **Step 1: 更新中文事实基线**

中文文档必须逐项写明：

```text
请求：filter/projection/sort/size/cursor
响应：list/nextCursor；nextCursor == null 时终止
性能：Mongo keyset、Elasticsearch search_after、无 count/offset/total
一致性：无 PIT、无跨请求快照、只向后
安全：有效 sort 必须是 EXACT、SINGLE 且不命中任何 Mask rule/alias；Schema unavailable fail-closed
token：后端 Base64URL continuation，不加密、不签名、不承载授权，不应记录日志
限制：后续请求保持 filter/sort；每次重新应用租户、授权、filter 与 SchemaMasker
配置：不存在 wow.query.cursor.encryption-key
```

Snapshot 文档同时给 typed/dynamic/state-only Gateway 与 API Client 示例；EventStream 只给 Gateway/HTTP 示例。

- [ ] **Step 2: 镜像更新英文文档**

英文使用相同标题层级、代码符号、route、错误边界和示例数据；`masking.md` 明确 ordinary sort remains allowed while CursorQuery rejects masked effective sort because raw sort values would otherwise enter `nextCursor`。

- [ ] **Step 3: 构建文档并扫描禁用配置**

```bash
cd documentation
pnpm docs:build
cd ..
! rg -n 'wow\.query\.cursor\.encryption-key|CursorTokenCodec|AES-256-GCM' documentation/docs
git diff --check
```

Expected: VitePress build PASS；用户文档无 encryption key/AES/Codec 残留。

- [ ] **Step 4: 提交文档**

```bash
git add documentation/docs
git commit -m "docs(query): document V9 cursor pagination"
```

---

### Task 9: 全量相关验证与交付证据

**Files:**
- Verify only: Tasks 1–8 的全部文件
- Modify only when a failing check proves a scoped CursorQuery defect

**Interfaces:**
- Consumes: 完整 V9 CursorQuery implementation。
- Produces: 可复现的本地行为、结构性性能、静态 Mask 与文档证据；不产生发布或生产性能声明。

- [ ] **Step 1: 核对提交与工作树范围**

```bash
git status --short
git log --oneline origin/agent/static-annotation-mask-v9-main..HEAD
git diff --stat origin/agent/static-annotation-mask-v9-main...HEAD
git diff --check origin/agent/static-annotation-mask-v9-main...HEAD
```

Expected: 只有设计、计划、CursorQuery 源码/测试/文档提交；无 secret、IDE 状态、build output 或现有 PR 历史改写。

- [ ] **Step 2: 扫描删除的加密设计**

```bash
! rg -n 'wow\.query\.cursor\.encryption-key|CursorTokenCodec|AES/GCM|AES-256-GCM' \
  wow-api wow-query wow-mongo wow-elasticsearch wow-webflux wow-openapi wow-apiclient \
  wow-schema wow-spring wow-spring-boot-starter test documentation/docs schema
```

Expected: 0 matches。`document/design` 保留历史删除决策，因此不纳入扫描。

- [ ] **Step 3: 运行全部相关模块 check**

```bash
./gradlew \
  :wow-api:check \
  :wow-query:check \
  :wow-schema:check \
  :wow-mongo:check \
  :wow-elasticsearch:check \
  :wow-webflux:check \
  :wow-openapi:check \
  :wow-apiclient:check \
  :wow-spring:check \
  :wow-spring-boot-starter:check \
  --stacktrace
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 运行真实 Backend integration tests**

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

Expected: BUILD SUCCESSFUL；共同 TCK 在两个后端通过。

- [ ] **Step 5: 重跑文档构建**

```bash
cd documentation
pnpm docs:build
```

Expected: 构建成功，无 broken links 或 Markdown/Mermaid 错误。

- [ ] **Step 6: 核对结构性性能与安全证据**

从 request capture、MockK 与 TCK 记录以下已验证事实：

```text
MongoDB: countDocuments=0; skip=0; limit=size+1; keyset uses effective sort
Elasticsearch: track_total_hits=false; from absent; PIT absent; size=size+1; continuation uses search_after
Mask: masked/unknown/non-EXACT effective sort rejected before storage; schema unavailable does not fallback
Gateway: CursorPage list masked before typed/dynamic return; nextCursor preserved
Token: Base64URL only; malformed/arity/non-scalar values fail as Invalid cursor
```

不得将这些结构性事实表述为具体延迟或吞吐提升；数值结论需要另行使用真实数据、索引与负载执行 explain/profile/压测。

- [ ] **Step 7: 最终自审与收尾提交**

```bash
git diff --check
git status --short
```

检查：无占位实现、无新依赖/配置、无手改生成客户端、无 `javap` 结果、PagedQuery/ListQuery 测试仍通过、未推送或创建/合并 PR。若最终验证产生必要的小修复，返回该文件所属任务，重复该任务的 RED→GREEN 命令，并使用该任务列出的精确 `git add` 路径提交 `fix(query): finalize V9 cursor pagination`。

Expected: 工作树 clean，所有本地证据来自最新 HEAD。
