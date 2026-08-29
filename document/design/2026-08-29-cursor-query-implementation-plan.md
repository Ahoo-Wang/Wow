# 通用游标分页 API 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Snapshot 与 EventStream 增加无状态、仅向后、无 total 的通用游标分页 API，并在 MongoDB 使用 keyset、在 Elasticsearch 使用 `search_after`。

**Architecture:** 新增与 `PagedQuery` 平行的 `CursorQuery`/`CursorPage` 契约，沿用 QueryService、QueryGateway、QueryFilter、Query Schema 和现有 HTTP/OpenAPI 管线。共享组件用 JDK AES-256-GCM 加密 opaque backend payload；MongoDB 以 BSON-native values 编译词典序范围条件，Elasticsearch 保留 scalar `FieldValue` 并直接传递 `search_after`，两端都读取 `size + 1` 条且不执行 count。

**Tech Stack:** Kotlin 2.4.10、JVM 17、Reactor、Jackson、MongoDB Reactive Streams Driver、Elasticsearch Java Client、Spring WebFlux、OpenAPI 3.1、JUnit Jupiter、MockK、Wow TCK。

**Spec:** `document/design/2026-08-29-cursor-query-design.md`

## Global Constraints

- 现有 `single`、`list`、`paged`、`count`、`aggregate` 源码行为与 HTTP wire shape 不变。
- `CursorQuery` 仅向后翻页，不返回 total，不提供 previous cursor，不创建服务端状态。
- 游标是 AES-256-GCM 加密、带认证 tag、无过期时间的版本化 Base64URL token；每次请求重新执行授权与请求作用域过滤。
- `wow.query.cursor.encryption-key` 是唯一的 Base64URL 32-byte key；缺失时不影响启动及既有查询，但内置 CursorQuery 从第一页起返回 unsupported；轮换 key 会使既有 cursor 失效。
- 未指定 sort 时使用模型唯一键升序；指定 sort 时自动追加唯一键升序。Snapshot 使用 `aggregateId`，EventStream 使用 `id`。
- 拒绝重复 sort 以及 `_score`、`_doc`、`_shard_doc`。
- user sort 与 effective sort 都复用 `AggregationQuery.MAX_SORT_FIELDS`（32）上限。
- Elasticsearch CursorQuery 不使用 PIT、不设置 from、关闭 `track_total_hits`。
- MongoDB CursorQuery 不调用 `countDocuments`、不设置 skip；业务复合索引不由框架自动创建。
- 不增加依赖、缓存、key ring、历史 key、JMH 或耗时阈值断言；只增加上述单 key 配置。
- 不运行 `javap`，不验证或声明 JVM 二进制兼容。
- 默认使用中文维护仓库文档；代码符号、错误信息和外部 API 名称保持英文。
- 未获得明确提交授权前，不执行 `git commit`；每个任务以 diff 与测试结果作为评审检查点。

---

## 文件结构

新增文件按现有职责落位：

- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/CursorQuery.kt`：公开查询请求契约。
- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/CursorPage.kt`：公开响应契约。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/CursorTokenCodec.kt`：所有后端共享的 opaque token 编解码。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/CursorQueries.kt`：effective sort 与稳定排序规则。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/CursorQueryDsl.kt`：Kotlin 查询 DSL。
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCursorFilterCompiler.kt`：MongoDB 词典序 keyset 条件。
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCursorDocuments.kt`：游标排序值读取和临时 projection 字段清理。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/CursorQueryHandlerFunction.kt`：通用 CursorQuery HTTP handler。
- Snapshot/EventStream 的 route factory、API Client 接口和测试文件继续沿用现有一类契约一个文件的组织方式。

不新建 CursorQueryService 子接口、游标存储接口、工厂或独立配置对象；配置复用 `QueryProperties` 的嵌套 `cursor` 属性。

## Final-review amendments（authoritative）

以下修订覆盖本计划早期 task 中与最终审查结论冲突的示例代码：

- `CursorTokenCodec` 只加密/解密 opaque bytes，token 为 version + fresh 96-bit nonce + AES-GCM ciphertext/tag，version 作为 AAD；不记录 key，不生成 fallback key。
- `QuerySchemaResolver` 只处理 filter/projection/sort capability 与物理路径；Mongo/Elasticsearch 在各自边界解密并校验 payload、arity 和物理 scalar kind。
- Mongo payload 使用 BSON-native codec，保留 `Date`、`BsonTimestamp`、`Decimal128`；Elasticsearch payload保留 null/boolean/string/long/double，数值 date/date_nanos 不受逻辑 temporal/string 类型阻止。
- malformed/authentication/payload/arity/value 错误无论 schema 是否可用都抛 `IllegalArgumentException`；HTTP 映射 400。
- `ReactiveSnapshotQueryApi` 与 `SynchronousSnapshotQueryApi` 不继承 cursor 接口；cursor client 是显式 opt-in。`QueryType` 新增 enum 成员会要求外部穷尽 `when` 增加分支，因此兼容性说明不再声明完整源码兼容。

---

### Task 1: 公开 CursorQuery 与 CursorPage 契约

**Files:**
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/CursorQuery.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/CursorPage.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/QueryProtocol.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/CursorQueryTest.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/CursorPageTest.kt`

**Interfaces:**
- Produces: `ICursorQuery`, `CursorQuery`, `CursorPage<T>`。
- Consumes: 现有 `Queryable`, `FilterExpression`, `Projection`, `Sort`, `MatchAllFilter`。

- [ ] **Step 1: 写失败的 API 边界测试**

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
    fun `should reject size without lookahead capacity`() {
        assertThrows<IllegalArgumentException> { CursorQuery(MatchAllFilter, size = 0) }
        assertThrows<IllegalArgumentException> { CursorQuery(MatchAllFilter, size = Int.MAX_VALUE) }
    }

    @Test
    fun `should rewrite filter and projection`() {
        val query = CursorQuery(MatchAllFilter, size = 20, cursor = "cursor")
            .withFilter(IdFilter("id"))
            .withProjection(Projection(include = listOf("state.name")))

        query.filter.assert().isEqualTo(IdFilter("id"))
        query.projection.include.assert().containsExactly("state.name")
        query.size.assert().isEqualTo(20)
        query.cursor.assert().isEqualTo("cursor")
    }
}
```

`CursorPageTest` 覆盖 nullable `nextCursor` 与 JSON round-trip：

```kotlin
@Test
fun `should round trip cursor page`() {
    val page = CursorPage(listOf("one"), "next")
    val json = jacksonObjectMapper().writeValueAsString(page)
    jacksonObjectMapper().readValue(json, CursorPage::class.java)
        .nextCursor.assert().isEqualTo("next")
}
```

- [ ] **Step 2: 运行测试确认失败原因是类型尚不存在**

Run:

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.CursorQueryTest" --tests "me.ahoo.wow.api.query.CursorPageTest" --stacktrace
```

Expected: Kotlin 编译失败，报告 `CursorQuery`、`CursorPage` 未解析。

- [ ] **Step 3: 实现最小公开契约**

`CursorQuery.kt`：

```kotlin
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class CursorQuery(
    @get:JsonIgnore(false)
    override val filter: FilterExpression,
    override val projection: Projection = Projection.ALL,
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

在 `QueryProtocol.QueryEnvelope` 增加统一属性名：

```kotlin
const val SIZE = "size"
const val CURSOR = "cursor"
```

不增加 `Condition` 构造器或 legacy cursor schema：CursorQuery 是新 API，只接受 canonical `filter`。

- [ ] **Step 4: 运行 API 测试**

Run:

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.CursorQueryTest" --tests "me.ahoo.wow.api.query.CursorPageTest" --stacktrace
```

Expected: PASS。

- [ ] **Step 5: 评审检查点**

Run:

```bash
git diff --check
git diff -- wow-api
```

Expected: 无 whitespace error；diff 只包含公开契约与对应测试，不包含后端代码。

---

### Task 2: 游标令牌、Schema、Gateway 与过滤管线

**Files:**
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/CursorTokenCodec.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/CursorQueries.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryService.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryGateway.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryType.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryContext.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaProviderResolution.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/filter/SnapshotQueryFilter.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/filter/EventStreamQueryFilter.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/MaskingDynamicDocumentQueryFilter.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/filter/MaskingSnapshotQueryFilter.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/mask/DataMasking.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/mask/AggregateDataMasker.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotStates.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/CursorQueryDsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/Dsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/QueryDsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/QueryDsl.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/CursorTokenCodecTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/CursorQueriesTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/CursorQueryDslTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryServiceCompatibilityTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewaySubscriptionTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/MaskingDynamicDocumentQueryFilterTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/MaskingSnapshotQueryFilterTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/filter/MaskingEventStreamQueryFilterTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/mask/DataMaskingKtTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/mask/DefaultAggregateDataMaskerTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/SnapshotStatesKtTest.kt`

**Interfaces:**
- Consumes: `ICursorQuery`, `CursorQuery`, `CursorPage<T>` from Task 1。
- Produces: `CursorTokenCodec.encode/decode`, `ICursorQuery.withUniqueSort`, `QueryType.CURSOR`, `QueryType.DYNAMIC_CURSOR`, Gateway/Service cursor operations。

- [ ] **Step 1: 写失败的 AES-GCM opaque token 与 effective sort 测试**

测试使用 32-byte Base64URL key 创建 codec，并验证 opaque bytes round-trip、同一 payload 的 fresh nonce、token bytes 不含 distinctive raw value，以及 malformed Base64URL、短 token、未知版本、篡改、错误 key 和非法 key 全部失败。effective sort 同时覆盖空 sort、唯一键已存在、重复/禁止字段、32 个字段已形成全序、33 个 user sort 和唯一键 append overflow。

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.CursorTokenCodecTest" --tests "me.ahoo.wow.query.CursorQueriesTest" --stacktrace
```

Expected: 编译失败，报告 AES-GCM codec/effective sort 边界尚不存在。

- [ ] **Step 3: 使用 JDK JCA 实现共享 AES-GCM codec**

`CursorTokenCodec.fromBase64Url` 只接受 32-byte key。`encode(ByteArray)` 生成 fresh 96-bit nonce，以 version byte 为 AAD，输出 version + nonce + ciphertext/tag 的 Base64URL；`decode` 对任何 envelope/authentication 失败统一抛出无敏感细节的 `IllegalArgumentException("Invalid cursor.")`。共享层不得知道 backend values 或 JSON schema。

`CursorQueries.kt`：

```kotlin
private val FORBIDDEN_CURSOR_SORTS = setOf("_score", "_doc", "_shard_doc")

fun ICursorQuery.withUniqueSort(uniqueField: String): ICursorQuery {
    val fields = sort.map(Sort::field)
    require(fields.distinct().size == fields.size) { "Cursor sort fields must be unique." }
    require(fields.none(FORBIDDEN_CURSOR_SORTS::contains)) { "Cursor sort contains an unstable metadata field." }
    val effectiveSort = if (uniqueField in fields) sort else sort + Sort(uniqueField, Sort.Direction.ASC)
    require(effectiveSort.size <= AggregationQuery.MAX_SORT_FIELDS)
    return CursorQuery(filter, projection, effectiveSort, size, cursor)
}
```

- [ ] **Step 4: 写失败的 Schema 与 Pipeline 分发测试**

在 `QuerySchemaResolverTest` 添加：

```kotlin
@Test
fun `cursor query should resolve fields without decoding backend cursor payload`() {
    val query = CursorQuery(
        MatchAllFilter,
        sort = listOf(Sort("state.createdAt", Sort.Direction.ASC), Sort("aggregateId", Sort.Direction.ASC)),
        cursor = "backend-owned-token",
    )

    val resolved = resolver.resolve(query).requireAccepted(QuerySchemaValidationMode.STRICT)
    resolved.sort.map(Sort::field).assert().containsExactly("document.created_at", "document.aggregate_id")
}
```

schema availability 只影响 filter/projection/sort capability；cursor payload 校验留给 backend。`QueryServiceCompatibilityTest` 添加默认不支持：

```kotlin
@Test
fun `legacy query service should inherit unsupported cursor`() {
    LegacyQueryService().cursor(CursorQuery(MatchAllFilter)).test()
        .expectErrorMessage("Cursor query is not supported.")
        .verify()
}
```

`QueryGatewaySubscriptionTest.operationPublishers` 加入 typed/dynamic cursor，断言重复订阅仍创建独立 QueryContext。

- [ ] **Step 5: 扩展 QueryService、Gateway、Context 与 Schema Resolver**

`QueryService` 默认方法：

```kotlin
fun cursor(query: ICursorQuery): Mono<CursorPage<R>> =
    Mono.error(UnsupportedOperationException("Cursor query is not supported."))

fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<DynamicDocument>> =
    Mono.error(UnsupportedOperationException("Cursor query is not supported."))
```

`QueryGateway` 提供同样的默认错误；`AbstractQueryGateway` 覆盖为：

```kotlin
override fun cursor(namedAggregate: NamedAggregate, query: ICursorQuery): Mono<CursorPage<R>> =
    mono(namedAggregate, QueryType.CURSOR, query)

override fun dynamicCursor(
    namedAggregate: NamedAggregate,
    query: ICursorQuery,
): Mono<CursorPage<DynamicDocument>> = mono(namedAggregate, QueryType.DYNAMIC_CURSOR, query)
```

`QueryContext` 增加：

```kotlin
fun <E : Any> asCursorQuery(): QueryContext<ICursorQuery, Mono<CursorPage<E>>> =
    this as QueryContext<ICursorQuery, Mono<CursorPage<E>>>
```

`QuerySchemaResolver.resolve(ICursorQuery)` 只解析 filter、projection 和每个 sort 的 `SORT` binding，返回保留 size/cursor、替换物理 sort path 的 `CursorQuery`。`QueryModelSchemaProvider.resolve(ICursorQuery, mode)` 遵循现有 fallback 规则，不解密或校验 cursor structure/value；所选 backend 负责统一错误语义。

- [ ] **Step 6: 接入 Tail Filter、masking、state conversion 与 DSL**

在 Snapshot/EventStream tail filter 的 `when` 中加入：

```kotlin
QueryType.CURSOR -> context.asCursorQuery<MaterializedSnapshot<S>>().setResult(queryService::cursor)
QueryType.DYNAMIC_CURSOR -> context.asCursorQuery<DynamicDocument>().setResult(queryService::dynamicCursor)
```

EventStream typed 分支使用 `DomainEventStream`。Dynamic masking 对 `CursorPage.list` 映射，保留 nextCursor：

```kotlin
fun <MASKER : DynamicDocumentMasker> AggregateDataMasker<MASKER>.mask(
    page: CursorPage<DynamicDocument>,
): CursorPage<DynamicDocument> = if (page.list.isEmpty() || isEmpty()) page else page.copy(
    list = page.list.map(::mask),
)
```

Snapshot state conversion：

```kotlin
fun <S : DynamicDocument> Mono<CursorPage<S>>.toStateDocumentCursorPage(): Mono<CursorPage<DynamicDocument>> =
    map { page -> page.copy(list = page.list.map(DynamicDocument::toState)) }
```

`CursorQueryDsl` 只增加 `size(Int)` 与 `cursor(String?)`，其他能力继承 `QueryableDsl`：

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

Snapshot `QueryDsl.kt` 返回 `Mono<CursorPage<MaterializedSnapshot<S>>>` 或 `Mono<CursorPage<DynamicDocument>>`；EventStream `QueryDsl.kt` 返回 `Mono<CursorPage<DomainEventStream>>` 或 `Mono<CursorPage<DynamicDocument>>`。

- [ ] **Step 7: 运行 Query 模块测试**

Run:

```bash
./gradlew :wow-query:check --stacktrace
```

Expected: PASS；所有 `when(QueryType)` 已覆盖 CURSOR/DYNAMIC_CURSOR，旧兼容测试仍通过。

- [ ] **Step 8: 评审检查点**

Run:

```bash
git diff --check
git diff -- wow-query
```

Expected: 无新 capability 子接口、无依赖、无服务端状态；Query Pipeline 只新增平行分支。

---

### Task 3: MongoDB keyset 与 projection 安全

**Files:**
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCursorFilterCompiler.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCursorDocuments.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryService.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryService.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoCursorFilterCompilerTest.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoCursorDocumentsTest.kt`
- Modify: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryServiceTest.kt`

**Interfaces:**
- Consumes: `CursorTokenCodec`, `ICursorQuery.withUniqueSort`, `CursorPage`。
- Produces: built-in Mongo Snapshot/EventStream 的 typed/dynamic cursor 实现。

- [ ] **Step 1: 写失败的词典序编译测试**

```kotlin
@Test
fun `should compile mixed direction lexicographic cursor`() {
    val filter = MongoCursorFilterCompiler.compile(
        listOf(
            Sort("createdAt", Sort.Direction.DESC),
            Sort("_id", Sort.Direction.ASC),
        ),
        listOf(100, "id-1"),
    ).toBsonDocument()

    filter.toJson().assert().contains(
        "\"createdAt\": {\"\$lt\": 100}",
        "\"createdAt\": 100",
        "\"_id\": {\"\$gt\": \"id-1\"}",
    )
}
```

增加 ASC null、DESC null、missing 与 values 数量错误测试：

```kotlin
@Test
fun `ascending null should continue with non null values`() {
    MongoCursorFilterCompiler.compile(
        listOf(Sort("rank", Sort.Direction.ASC)),
        listOf(null),
    ).toBsonDocument().toJson().assert().contains("\$ne")
}

@Test
fun `descending null should only continue through later tie breakers`() {
    MongoCursorFilterCompiler.compile(
        listOf(Sort("rank", Sort.Direction.DESC), Sort("_id", Sort.Direction.ASC)),
        listOf(null, "id-1"),
    ).toBsonDocument().toJson().assert().contains("\$expr", "\$gt")
}

@Test
fun `cursor values should match sort arity`() {
    assertThrows<IllegalArgumentException> {
        MongoCursorFilterCompiler.compile(
            listOf(Sort("rank", Sort.Direction.ASC)),
            emptyList(),
        )
    }
}
```

`MongoCursorDocumentsTest` 用一个缺少 `rank` 的 Document 断言 `valueAt("rank")` 编码为 JSON null，从而与 MongoDB 将 missing 按 null 排序的行为一致。

- [ ] **Step 2: 运行编译器测试确认失败**

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.MongoCursorFilterCompilerTest" --stacktrace
```

Expected: 编译失败，报告 `MongoCursorFilterCompiler` 不存在。

- [ ] **Step 3: 实现最小词典序编译器**

```kotlin
internal object MongoCursorFilterCompiler {
    fun compile(sort: List<Sort>, values: List<Any?>): Bson {
        require(sort.size == values.size) { "Cursor values must match effective sort fields." }
        require(sort.size <= AggregationQuery.MAX_SORT_FIELDS)
        return Filters.or(sort.indices.map { index ->
            Filters.and(buildList {
                repeat(index) { equalIndex ->
                    add(Filters.eq(sort[equalIndex].field, values[equalIndex]))
                }
                add(after(sort[index], values[index]))
            })
        })
    }

    private fun after(sort: Sort, value: Any?): Bson = when {
        value == null && sort.direction == Sort.Direction.ASC -> Filters.ne(sort.field, null)
        value == null -> Document("\$expr", false)
        sort.direction == Sort.Direction.ASC -> Filters.gt(sort.field, value)
        else -> Filters.or(
            Filters.lt(sort.field, value),
            Filters.eq(sort.field, null),
        )
    }
}
```

backend BSON payload 使用 `RawBsonDocument`/`DocumentCodec` 加密前后保留 null、string、boolean、Int、Long、Double、`Date`、`BsonTimestamp`、`Decimal128`；其他值、arity mismatch 与超过 32 个字段均抛 `IllegalArgumentException`。

- [ ] **Step 4: 写 projection 与请求结构测试**

覆盖：

```kotlin
@Test
fun `cursor should use lookahead without count or skip`() {
    service.cursor(CursorQuery(MatchAllFilter, size = 2)).block()

    verify(exactly = 1) { publisher.limit(3) }
    verify(exactly = 0) { publisher.skip(any()) }
    verify(exactly = 0) { collection.countDocuments(any<Bson>()) }
}

@Test
fun `projection should not leak internally included sort fields`() {
    val page = service.dynamicCursor(
        CursorQuery(
            MatchAllFilter,
            projection = Projection(include = listOf("state.name")),
            sort = listOf(Sort("state.createdAt", Sort.Direction.ASC)),
            size = 1,
        ),
    ).block()!!

    page.list.single().containsKey("createdAt").assert().isFalse()
    page.nextCursor.assert().isNotNull()
}
```

- [ ] **Step 5: 实现 Mongo cursor 查询流**

在 `AbstractMongoQueryService` 增加兼容性默认：

```kotlin
protected open val cursorUniqueField: String? = null
protected open fun resolve(query: ICursorQuery): Mono<ICursorQuery> = Mono.just(query)
```

`cursorUniqueField == null` 时返回与 QueryService 默认相同的 unsupported error；内置 Snapshot/EventStream 分别覆盖为 `aggregateId`、`id`。

核心查询流：

```kotlin
private fun <T : Any> cursorDocument(
    query: ICursorQuery,
    mapper: (Document) -> T,
): Mono<CursorPage<T>> {
    val uniqueField = cursorUniqueField
        ?: return Mono.error(UnsupportedOperationException("Cursor query is not supported."))
    val effective = query.withUniqueSort(uniqueField)
    return resolve(effective).flatMap { resolved ->
        val values = resolved.cursor?.let(CursorTokenCodec::decode)
        val filter = values?.let {
            Filters.and(converter.convert(resolved.filter), MongoCursorFilterCompiler.compile(resolved.sort, it))
        } ?: converter.convert(resolved.filter)
        val projection = resolved.projection.withCursorFields(resolved.sort.map(Sort::field))
        collection.find(filter)
            .projection(projectionConverter.convert(projection.queryProjection))
            .sort(sortConverter.convert(resolved.sort))
            .limit(resolved.size + 1)
            .toFlux().collectList()
            .map { documents -> documents.toCursorPage(resolved, projection, mapper) }
    }
}
```

`MongoCursorDocuments.kt` 必须提供：

- 依据 include/exclude 计算为读取 sort values 临时补充的物理字段；
- 按点分 path 读取嵌套 Document 值；
- 在生成 token 后只移除内部补充字段；
- 若用户本来包含 sort 字段，不删除；
- 先截取前 size 条，再用最后一条返回记录生成 nextCursor，不能用 lookahead 记录生成。

使用以下窄数据结构和函数签名，不增加接口层：

```kotlin
internal data class MongoCursorProjection(
    val queryProjection: Projection,
    val internalFields: Set<String>,
)

internal fun Projection.withCursorFields(sortFields: List<String>): MongoCursorProjection {
    fun returns(path: String): Boolean = when {
        include.isNotEmpty() -> include.any { path == it || path.startsWith("$it.") }
        else -> exclude.none { path == it || path.startsWith("$it.") }
    }
    val internal = sortFields.filterNot(::returns).toSet()
    val augmented = when {
        include.isNotEmpty() -> copy(include = (include + internal).distinct())
        exclude.isNotEmpty() -> copy(
            exclude = exclude.filterNot { excluded ->
                internal.any { field -> field == excluded || field.startsWith("$excluded.") }
            },
        )
        else -> this
    }
    return MongoCursorProjection(augmented, internal)
}

private fun Document.valueAt(path: String): Any? =
    path.split('.').fold(this as Any?) { current, part -> (current as? Document)?.get(part) }

private fun Document.removeAt(path: String) {
    val parts = path.split('.')
    val parent = parts.dropLast(1).fold(this as Document?) { current, part -> current?.get(part) as? Document }
    parent?.remove(parts.last())
}

internal fun <T : Any> List<Document>.toCursorPage(
    query: ICursorQuery,
    projection: MongoCursorProjection,
    tokenCodec: CursorTokenCodec,
    mapper: (Document) -> T,
): CursorPage<T> {
    val returned = take(query.size)
    val nextCursor = if (size > query.size) {
        MongoCursorCodec.encode(tokenCodec, query.sort.map { sort -> returned.last().valueAt(sort.field) })
    } else {
        null
    }
    return CursorPage(
        list = returned.map { document ->
            projection.internalFields.forEach(document::removeAt)
            mapper(document)
        },
        nextCursor = nextCursor,
    )
}
```

为避免 nested document 被原 projection 整体排除，`MongoCursorDocumentsTest` 必须覆盖 exclude parent + sort child，并断言响应仍不含该 parent。

- [ ] **Step 6: 内置服务接入 schema resolution**

Snapshot：

```kotlin
override val cursorUniqueField: String = MessageRecords.AGGREGATE_ID
override fun resolve(query: ICursorQuery) = schemaProvider.resolve(query, validationMode)
```

EventStream 使用 `MessageRecords.ID`。effective sort 在 resolve 前追加，因此 Query Schema 同时解析业务字段与唯一字段。

- [ ] **Step 7: 运行 Mongo 单元检查**

Run:

```bash
./gradlew :wow-mongo:test --stacktrace
```

Expected: PASS；MockK 验证 cursor 路径无 count/skip。

- [ ] **Step 8: 评审检查点**

Run:

```bash
git diff --check
git diff -- wow-mongo
```

Expected: 没有自动建业务索引、没有 aggregation pipeline、没有服务端 cursor 状态。

---

### Task 4: Elasticsearch search_after 实现

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryService.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryService.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryService.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryServiceTest.kt`

**Interfaces:**
- Consumes: `CursorTokenCodec`, `withUniqueSort`, `CursorPage`。
- Produces: built-in Elasticsearch Snapshot/EventStream typed/dynamic cursor。

- [ ] **Step 1: 写失败的 request-shape 测试**

```kotlin
@Test
fun `dynamic cursor should use search after without total from or pit`() {
    val request = slot<SearchRequest>()
    every { elasticsearchClient.search(capture(request), Map::class.java) } returns Mono.just(
        searchResponseWithSortValues(1L to "id-1", 2L to "id-2", 3L to "id-3"),
    )

    val query = CursorQuery(
        MatchAllFilter,
        sort = listOf(Sort("version", Sort.Direction.ASC)),
        size = 2,
    )
    val page = queryService.dynamicCursor(query).block()!!

    request.captured.size().assert().isEqualTo(3)
    request.captured.from().assert().isNull()
    request.captured.trackTotalHits()!!.enabled().assert().isFalse()
    request.captured.pit().assert().isNull()
    request.captured.searchAfter().assert().isEmpty()
    page.list.assert().hasSize(2)
    page.nextCursor.assert().isNotNull()
    verify(exactly = 0) { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) }
}
```

第二次请求把第一页 token 传回，并覆盖末页与缺失 sort values：

```kotlin
@Test
fun `next cursor request should pass decoded search after`() {
    val requests = mutableListOf<SearchRequest>()
    every { elasticsearchClient.search(capture(requests), Map::class.java) } returnsMany listOf(
        Mono.just(searchResponseWithSortValues(1L to "id-1", 2L to "id-2", 3L to "id-3")),
        Mono.just(searchResponseWithSortValues(3L to "id-3")),
    )
    val query = CursorQuery(
        MatchAllFilter,
        sort = listOf(Sort("version", Sort.Direction.ASC)),
        size = 2,
    )

    val first = queryService.dynamicCursor(query).block()!!
    val second = queryService.dynamicCursor(query.copy(cursor = first.nextCursor)).block()!!

    requests[1].searchAfter().assert().containsExactly(FieldValue.of(2L), FieldValue.of("id-2"))
    second.nextCursor.assert().isNull()
}

@Test
fun `cursor hit should require complete sort values`() {
    every { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
        searchResponse(total = null),
    )
    assertThrows<IllegalArgumentException> {
        queryService.dynamicCursor(CursorQuery(MatchAllFilter, size = 1)).block()
    }
}
```

测试 helper 必须显式把 sort values 写入 hit，不能只构造 `_source`：

```kotlin
private fun searchResponseWithSortValues(
    vararg values: Pair<Long, String>,
): SearchResponse<Map<*, *>> = SearchResponse.of { response ->
    response.took(1).timedOut(false)
        .shards { it.failed(0).successful(1).total(1) }
        .hits { hits ->
            hits.hits(values.mapIndexed { index, (version, id) ->
                Hit.of<Map<*, *>> { hit ->
                    hit.index("test-index").id((index + 1).toString())
                        .source(mapOf("version" to version, "id" to id))
                        .sort(listOf(FieldValue.of(version), FieldValue.of(id)))
                }
            })
        }
}
```

调用形式使用 `1L to "id-1"`，避免定义未实现的可变参数重载。

- [ ] **Step 2: 运行 Elasticsearch 测试确认失败**

Run:

```bash
./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryServiceTest" --stacktrace
```

Expected: 编译失败，AbstractElasticsearchQueryService 尚未实现 cursor。

- [ ] **Step 3: 在 Elasticsearch backend payload 内实现 FieldValue scalar 编解码**

```kotlin
private fun JsonNode.toFieldValue(): FieldValue = when {
    isNull -> FieldValue.NULL
    isString -> FieldValue.of(asString())
    isBoolean -> FieldValue.of(booleanValue())
    isIntegralNumber -> FieldValue.of(longValue())
    isNumber -> FieldValue.of(doubleValue())
    else -> throw IllegalArgumentException("Cursor sort value must be scalar.")
}

private fun FieldValue.toCursorValue(): JsonNode = when {
    isNull -> JsonNodeFactory.instance.nullNode()
    isString -> JsonNodeFactory.instance.textNode(stringValue())
    isBoolean -> JsonNodeFactory.instance.booleanNode(booleanValue())
    isLong -> JsonNodeFactory.instance.numberNode(longValue())
    isDouble -> JsonNodeFactory.instance.numberNode(doubleValue())
    else -> throw IllegalArgumentException("Elasticsearch cursor sort value must be scalar.")
}
```

以上转换只属于 Elasticsearch boundary；转换后的 backend JSON bytes 再交给共享 AES-GCM codec。方法名已按当前 Elasticsearch Java Client 9.4.5 的 `FieldValue` 源码确认；不得改用 `toString()` 丢失类型。

- [ ] **Step 4: 实现独立 CursorQuery search request**

在 `AbstractElasticsearchQueryService` 增加 open nullable unique field 与 resolve hook，保持第三方子类源码兼容：

```kotlin
protected open val cursorUniqueField: String? = null
protected open val cursorTokenCodec: CursorTokenCodec? = null
protected open fun resolve(query: ICursorQuery): Mono<ICursorQuery> = Mono.just(query)
```

新 cursor 流不得复用会强制设置 from 的 `createSearchRequest`：

```kotlin
private fun cursorSearchRequest(query: ICursorQuery, resolved: ResolvedQuery): SearchRequest = SearchRequest.of {
    it.index(indexName)
        .query(resolved.query)
        .size(query.size + 1)
        .sort(resolved.sortOptions)
        .trackTotalHits { hits -> hits.enabled(false) }
    query.cursor?.let { cursor ->
        ElasticsearchCursorCodec.decode(requireNotNull(cursorTokenCodec), cursor, query.sort.size)
    }?.let { values ->
        it.searchAfter(values)
    }
    if (!query.projection.isEmpty()) it.source { source -> source.filter(query.projection.toSourceFilter()) }
    it
}
```

响应只返回前 size 个 hit；有 lookahead 时用第 size 个已返回 hit 的 `sort()` 生成 token。typed cursor 复用 dynamic cursor 后的 `toTypedResult`，不得二次搜索。

响应映射实现为：

```kotlin
private fun SearchResponse<Map<*, *>>.toCursorPage(query: ICursorQuery): CursorPage<DynamicDocument> {
    val returned = hits().hits().take(query.size)
    val nextCursor = if (hits().hits().size > query.size) {
        val sortValues = returned.last().sort()
        require(sortValues.size == query.sort.size) { "Invalid cursor." }
        ElasticsearchCursorCodec.encode(requireNotNull(cursorTokenCodec), sortValues)
    } else {
        null
    }
    return CursorPage(
        list = returned.mapNotNull { it.toDynamicDocument() },
        nextCursor = nextCursor,
    )
}
```

若命中结果缺少 `_source`，保持现有 dynamic 查询的跳过语义；nextCursor 仍取最后一个已返回 hit 的 sort values，不取 lookahead hit。

- [ ] **Step 5: 内置服务配置唯一字段和 Schema resolve**

Snapshot 使用 `MessageRecords.AGGREGATE_ID`，EventStream 使用 `MessageRecords.ID`，并分别增加 `resolve(ICursorQuery)`。不追加 `_shard_doc`，不调用 `ElasticsearchQueryPager` 或 `ElasticsearchPointInTime`。

- [ ] **Step 6: 运行 Elasticsearch 单元检查**

Run:

```bash
./gradlew :wow-elasticsearch:test --stacktrace
```

Expected: PASS；cursor 请求没有 from/PIT/total tracking，旧 ListQuery PIT 行为保持不变。

- [ ] **Step 7: 评审检查点**

Run:

```bash
git diff --check
git diff -- wow-elasticsearch
```

Expected: 现有 `ElasticsearchQueryPager` 未被改成公开游标状态；PagedQuery 仍使用原精确 total 语义。

---

### Task 5: 通用后端 TCK 与 WebFlux 路由

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryServiceSpec.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/CursorQueryHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/QueryBodyExtractor.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilter.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/CursorQuerySnapshotHandlerFunction.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/CursorQuerySnapshotStateHandlerFunction.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/CursorQueryEventStreamHandlerFunction.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/BuiltInHttpRoutes.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/QueryRouteModule.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/QueryBodyExtractorTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilterTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`

**Interfaces:**
- Consumes: 两个 built-in backend cursor 实现、QueryGateway cursor 方法。
- Produces: `/snapshot/cursor`、`/snapshot/cursor/state`、`/event/cursor` runtime handlers 和真实后端通用行为证据。

- [ ] **Step 1: 在 TCK 中写真实多页行为测试**

Snapshot TCK 保存三个不同 aggregateId、相同业务排序值的快照，然后：

```kotlin
val first = CursorQuery(
    MatchAllFilter,
    sort = listOf(Sort("version", Sort.Direction.ASC)),
    size = 2,
).query(snapshotQueryService).block()!!

first.list.assert().hasSize(2)
first.nextCursor.assert().isNotNull()

val second = CursorQuery(
    MatchAllFilter,
    sort = listOf(Sort("version", Sort.Direction.ASC)),
    size = 2,
    cursor = first.nextCursor,
).query(snapshotQueryService).block()!!

(first.list + second.list).map { it.aggregateId.id }.distinct().assert().hasSize(3)
second.nextCursor.assert().isNull()
```

EventStream TCK 使用三个 event stream，按 version + id 验证相同规则。再分别增加 DESC、多字段、null/缺失排序字段和 projection 排除 sort 字段用例。

- [ ] **Step 2: 运行真实后端 TCK 确认失败或暴露差异**

Run:

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

Expected before 修复完成: 新 Cursor TCK 至少一项失败；失败必须定位到 Mongo keyset、ES search_after 或 projection，不允许删除用例规避。

- [ ] **Step 3: 修正后端直至共同 TCK 通过**

只修改 Task 3/4 已定义的 compiler、document helper 和 request builder。若 null/missing 行为不同，在 Mongo compiler 显式补分支；不得引入服务端状态、PIT 或查询指纹。

Run:

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

Expected: PASS。

- [ ] **Step 4: 写失败的 HTTP body、guard 与 state-only 测试**

```kotlin
@Test
fun `cursor body should reject unknown properties`() {
    cursorClient().post().uri("/sku/snapshot/cursor")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""{"filter":{"op":"MATCH_ALL"},"size":10,"unexpected":true}""")
        .exchange().expectStatus().isBadRequest
}

@Test
fun `cursor should use max page size without page window`() {
    val guard = guard(maxPageSize = 2, maxPageWindow = 1)
    guard.filter(cursorContext(CursorQuery(MatchAllFilter, size = 2)), EmptyFilterChain.instance())
        .contextWrite(Context.of(RAW_REQUEST_KEY, request))
        .test().verifyComplete()
}
```

另测 `size=3` 被 max-page-size 拒绝、match-all cursor 在 `allowExpensiveOperators=false` 时不因 counting 规则拒绝、昂贵 filter 仍拒绝、idle timeout 应用于 Mono CursorPage：

```kotlin
assertThrows<IllegalArgumentException> {
    guard(maxPageSize = 2).validateForTest(CursorQuery(MatchAllFilter, size = 3))
}
guard(maxPageSize = 2, allowExpensiveOperators = false)
    .filter(cursorContext(CursorQuery(MatchAllFilter, size = 2)), EmptyFilterChain.instance())
    .contextWrite(Context.of(RAW_REQUEST_KEY, request)).test().verifyComplete()
assertThrows<IllegalArgumentException> {
    guard(allowExpensiveOperators = false).validateForTest(
        CursorQuery(ContainsFilter(LogicalField("state.name"), "x")),
    )
}
```

不要为测试暴露生产 `validate`；在测试文件中定义本地 helper，通过完整 filter chain 订阅：

```kotlin
private fun HttpQueryGuardFilter.validateForTest(query: ICursorQuery) {
    filter(cursorContext(query), EmptyFilterChain.instance())
        .contextWrite(Context.of(RAW_REQUEST_KEY, request))
        .block()
}
```

测试 helper 与现有 `countClient()`/`pagedContext()` 保持同一文件内的窄封装：

```kotlin
private fun cursorContext(query: ICursorQuery) =
    DefaultQueryContext<ICursorQuery, Mono<CursorPage<Any>>>(
        QueryType.CURSOR,
        MOCK_AGGREGATE_METADATA,
    ).setQuery(query).setResult(Mono.just(CursorPage(emptyList(), null)))

private fun cursorClient(queryGateway: QueryGateway<*> = RouteTestFixtures.snapshotQueryGateway): WebTestClient {
    val handler = CursorQueryHandlerFunctionFactory(
        BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY,
        queryGateway,
        DefaultRewriteRequestFilter,
        WebFluxRequestExceptionHandler(),
    ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY))
    return WebTestClient.bindToRouterFunction(route(POST("/sku/snapshot/cursor"), handler)).build()
}
```

- [ ] **Step 5: 实现通用 CursorQuery handler**

```kotlin
class CursorQueryHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val queryGateway: QueryGateway<*>,
    private val rewriteRequestFilter: RewriteRequestFilter,
    private val exceptionHandler: RequestExceptionHandler,
    private val rewriteResult: (Mono<CursorPage<DynamicDocument>>) -> Mono<CursorPage<DynamicDocument>> = { it },
) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> =
        request.body(QueryBodyExtractor.CURSOR_QUERY_EXTRACTOR)
            .flatMap { body ->
                val query = rewriteRequestFilter.rewrite(aggregateMetadata, request, body)
                rewriteResult(queryGateway.dynamicCursor(aggregateMetadata, query)).writeRawRequest(request)
            }.toServerResponse(request, exceptionHandler)
}
```

Snapshot state factory 使用 `toStateDocumentCursorPage()`；EventStream 不改写。`QueryRouteModule` 注册三个 factory。

同时在 `BuiltInHttpRouteHandlerKeys` 增加 Task 5 编译所需常量：

```kotlin
object Snapshot {
    const val CURSOR_QUERY = "$AGGREGATE_SNAPSHOT.cursor-query"
    const val CURSOR_QUERY_STATE = "$AGGREGATE_SNAPSHOT.cursor-query-state"
}

object Event {
    const val CURSOR_QUERY = "$AGGREGATE_EVENT.cursor-query"
}
```

- [ ] **Step 6: 扩展 HTTP guard**

在 validate query dispatch 中先处理 `ICursorQuery`：

```kotlin
is ICursorQuery -> require(query.size <= maxPageSize || maxPageSize == 0) {
    "HTTP cursor size[${query.size}] must be between 1 and $maxPageSize."
}
```

CURSOR/DYNAMIC_CURSOR 不加入 `COUNTING_QUERY_TYPES`，但继续执行 filter 节点、值数量和 expensive operator 校验。idle timeout 分支：

```kotlin
QueryType.CURSOR, QueryType.DYNAMIC_CURSOR ->
    context.asCursorQuery<Any>().rewriteResult { it.timeout(idleTimeout) }
```

- [ ] **Step 7: 运行 WebFlux 与 starter 检查**

Run:

```bash
./gradlew :wow-webflux:check :wow-spring-boot-starter:check --stacktrace
```

Expected: PASS；Cursor body 严格解析、state-only 保留 nextCursor、guard 不计算 page window。

- [ ] **Step 8: 评审检查点**

Run:

```bash
git diff --check
git diff -- test/wow-tck wow-webflux wow-spring-boot-starter
```

Expected: 没有 SSE cursor route；除 `wow.query.cursor.encryption-key` 外没有新配置，TCK 同时约束 MongoDB 与 Elasticsearch。

---

### Task 6: OpenAPI、JSON Schema、API Client 与文档

**Files:**
- Create: `schema/query/v2/cursor-query.schema.json`
- Create: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/CursorQuerySchemaTest.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/QueryComponent.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/QueryContractComponentSupport.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/snapshot/SnapshotRouteContributor.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/event/EventRouteContributor.kt`
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiCompatibilitySnapshotTest.kt`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json`
- Create: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SnapshotCursorQueryApi.kt`
- Create: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/ReactiveSnapshotCursorQueryApi.kt`
- Create: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SynchronousSnapshotCursorQueryApi.kt`
- Modify: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/ReactiveSnapshotQueryApi.kt`
- Modify: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SynchronousSnapshotQueryApi.kt`
- Create: `wow-apiclient/src/test/kotlin/me/ahoo/wow/apiclient/query/SnapshotCursorQueryApiTest.kt`
- Modify: `documentation/docs/zh/guide/query/snapshot-query.md`
- Modify: `documentation/docs/en/guide/query/snapshot-query.md`
- Modify: `documentation/docs/zh/guide/query/event-stream-query.md`
- Modify: `documentation/docs/en/guide/query/event-stream-query.md`
- Modify: `documentation/docs/zh/guide/query/query-gateway.md`
- Modify: `documentation/docs/en/guide/query/query-gateway.md`
- Modify: `documentation/docs/zh/guide/query/query-api-client.md`
- Modify: `documentation/docs/en/guide/query/query-api-client.md`
- Modify: `documentation/docs/zh/guide/open-api.md`
- Modify: `documentation/docs/en/guide/open-api.md`
- Modify: `documentation/docs/zh/reference/config/infrastructure.md`
- Modify: `documentation/docs/en/reference/config/infrastructure.md`

**Interfaces:**
- Consumes: runtime handler keys/routes 和 public CursorQuery/CursorPage。
- Produces: discoverable OpenAPI/Schema、Snapshot API Client 和用户文档。

- [ ] **Step 1: 写 JSON Schema 与 API Client 失败测试**

`schema/query/v2/cursor-query.schema.json` 目标内容：

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/cursor-query.schema.json",
  "title": "Cursor Query",
  "type": "object",
  "properties": {
    "filter": { "$ref": "filter-expression.schema.json" },
    "projection": { "$ref": "../definitions.schema.json#/definitions/projection" },
    "sort": { "type": "array", "items": { "$ref": "../definitions.schema.json#/definitions/sort" }, "default": [] },
    "size": { "type": "integer", "minimum": 1, "maximum": 2147483646, "default": 10 },
    "cursor": { "type": ["string", "null"], "default": null }
  },
  "required": ["filter"],
  "additionalProperties": false
}
```

API Client test 通过 Spring reflection 断言三个方法路径分别是 `snapshot/cursor` 和 `snapshot/cursor/state`，返回类型包含 `CursorPage`：

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
    val api = object : ReactiveSnapshotCursorQueryApi<Any> {
        override fun cursor(query: ICursorQuery): Mono<CursorPage<MaterializedSnapshot<Any>>> = Mono.empty()
        override fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<Map<String, Any>>> = Mono.empty()
        override fun cursorState(query: ICursorQuery): Mono<CursorPage<Any>> = Mono.empty()
    }
    val typed: Mono<CursorPage<MaterializedSnapshot<Any>>> = api.cursor(CursorQuery(MatchAllFilter))
    typed.assert().isNotNull()
}
```

Raw schema test 从模块目录读取仓库文件并校验边界：

```kotlin
class CursorQuerySchemaTest {
    private val schema = JsonSerializer.readTree(
        Files.readString(Path.of("../schema/query/v2/cursor-query.schema.json")),
    )

    @Test
    fun `cursor query schema should require filter and bound size`() {
        schema["required"].map(JsonNode::asString).assert().containsExactly("filter")
        schema["additionalProperties"].booleanValue().assert().isFalse()
        val size = schema["properties"]["size"]
        size["minimum"].intValue().assert().isOne()
        size["maximum"].intValue().assert().isEqualTo(Int.MAX_VALUE - 1)
    }
}
```

- [ ] **Step 2: 运行 schema/client 测试确认失败**

Run:

```bash
./gradlew :wow-schema:test :wow-apiclient:test --stacktrace
```

Expected: Cursor schema/client 类型尚未接入时失败。

- [ ] **Step 3: 实现 Snapshot API Client**

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

Reactive 分别返回 `Mono<CursorPage<MaterializedSnapshot<S>>>`、`Mono<CursorPage<Map<String, Any>>>`、`Mono<CursorPage<S>>`；Synchronous 返回对应的非 Mono 类型。`ReactiveSnapshotQueryApi` 与 `SynchronousSnapshotQueryApi` 保持原父接口集合，cursor 只能通过显式 opt-in 的两个 cursor 接口使用。仓库当前没有 EventStream API Client，不为本功能额外创建整套 Event Client。

- [ ] **Step 4: 写失败的 OpenAPI route/schema 断言**

在 `OpenApiCompatibilitySnapshotTest` 增加显式语义断言：

```kotlin
val paths = mapper.valueToTree<JsonNode>(openAPI).path("paths")
paths.fieldNames().asSequence().any { it.endsWith("/snapshot/cursor") }.assert().isTrue()
paths.fieldNames().asSequence().any { it.endsWith("/snapshot/cursor/state") }.assert().isTrue()
paths.fieldNames().asSequence().any { it.endsWith("/event/cursor") }.assert().isTrue()

val cursorSchema = mapper.valueToTree<JsonNode>(openAPI)
    .path("components").path("schemas").path("wow.api.query.CursorQuery")
cursorSchema.path("properties").has("cursor").assert().isTrue()
cursorSchema.path("properties").has("size").assert().isTrue()
cursorSchema.path("properties").has("pagination").assert().isFalse()
```

- [ ] **Step 5: 扩展 OpenAPI components 与 route contributors**

新增 `CURSOR_QUERY_SUFFIX/KEY`、CursorQuery request body 和 `CursorPage<T>` JSON response helpers。Snapshot route contributor 为所有现有 tenant/owner variants 增加 cursor 与 cursor/state；Event route contributor 增加 event/cursor。三个 route 不设置 streaming accept。

Task 6 直接复用 Task 5 已新增的 built-in handler keys，不重复修改或重新命名。

- [ ] **Step 6: 更新并审查 OpenAPI snapshots**

Run:

```bash
./gradlew :wow-openapi:test -Dwow.snapshot.update=true --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" --stacktrace
git diff -- wow-openapi/src/test/resources/openapi
```

Expected: snapshot 只新增 CursorQuery/CursorPage components 与三组 route variants；现有 route/method/response 不变。

再运行非更新模式：

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" --stacktrace
```

Expected: PASS。

- [ ] **Step 7: 更新中英文文档**

文档必须明确：

- CursorQuery 的 JSON 请求/响应示例；
- `nextCursor == null` 的终止条件；
- 同一 cursor 后续请求必须保持 filter/sort；
- 无 total、无 previous cursor、无快照一致性；
- MongoDB 复合索引要求与 Elasticsearch search_after；
- `max-page-size` 同时约束 paged 和 cursor；
- Snapshot API Client typed/dynamic/state-only 用法；
- CursorQuery 不能修复缺索引和昂贵 filter。

英文文档与中文文档语义一致，不机械复制中文标点。

- [ ] **Step 8: 运行契约与文档检查**

Run:

```bash
./gradlew :wow-schema:check :wow-apiclient:check :wow-openapi:check --stacktrace
cd documentation && pnpm docs:build
```

Expected: Gradle tasks PASS，VitePress build 成功且无断链。

- [ ] **Step 9: 评审检查点**

Run:

```bash
git diff --check
git diff -- schema wow-openapi wow-apiclient documentation
```

Expected: 不包含生成客户端、构建产物、`node_modules`；旧 schema/route 未被收紧。

---

### Task 7: 全量相关验证与交付证据

**Files:**
- Verify only: all files from Tasks 1-6
- Modify only when a failing check proves a scoped defect in the cursor implementation

**Interfaces:**
- Consumes: 完整 CursorQuery 实现。
- Produces: 本地行为、结构性性能和文档构建证据；不产出发布或生产性能声明。

- [ ] **Step 1: 确认工作树范围**

Run:

```bash
git status --short
git diff --stat
git diff --check
```

Expected: 只有设计文档、实施计划及 CursorQuery 相关源码/测试/文档；无 secret、IDE 状态、build output。

- [ ] **Step 2: 运行全部相关模块 check**

Run:

```bash
./gradlew \
  :wow-api:check \
  :wow-query:check \
  :wow-mongo:check \
  :wow-elasticsearch:check \
  :wow-webflux:check \
  :wow-openapi:check \
  :wow-schema:check \
  :wow-apiclient:check \
  :wow-spring-boot-starter:check \
  --stacktrace
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 运行真实后端 integration tests**

Run:

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

Expected: BUILD SUCCESSFUL；Cursor TCK 覆盖 MongoDB 与 Elasticsearch。

- [ ] **Step 4: 运行文档构建**

Run:

```bash
cd documentation
pnpm docs:build
```

Expected: 成功生成站点，无 broken link 或 Markdown 构建错误。

- [ ] **Step 5: 核对结构性性能证据**

从测试输出与 request capture 汇总以下已验证事实：

```text
MongoDB: countDocuments calls = 0; skip calls = 0; limit = size + 1
Elasticsearch: track_total_hits = false; from absent; PIT absent; size = size + 1; next request has search_after
```

不得把这些结构性断言写成具体延迟/吞吐提升。若需要数值，另行使用真实数据、索引和负载执行 explain/profile/压测。

- [ ] **Step 6: 最终 diff 自审**

Run:

```bash
git diff --check
git status --short
```

检查：

- 无未完成标记或占位实现；
- 无 `javap` 结果或 JVM 二进制兼容声明；
- 无新依赖/配置；
- 无手改生成客户端；
- 旧 PagedQuery 与 ListQuery 测试仍通过；
- 未经授权不提交、不推送、不创建 PR。

Expected: diff 干净、范围符合设计、所有本地证据可复现。
