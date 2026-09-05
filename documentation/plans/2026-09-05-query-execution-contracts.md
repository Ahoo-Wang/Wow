# 查询执行合同 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 ES 游标请求与结果的订阅隔离，以及 MongoDB 排除逻辑主键的投影结果转换。

**Architecture:** ES 保留请求编译与 token 校验，只延后客户端搜索。Mongo 两个内建查询 Backend 共用一个 internal 映射函数，主键存在时复用原严格重命名函数，缺席时直接转换 JSON。Gateway、Schema 与持久化转换不变。

**Tech Stack:** Kotlin、JVM 17、Reactor、Spring Data Elasticsearch 6.1.1、MongoDB reactive driver、JUnit、MockK、FluentAssert、Testcontainers。

**Spec:** [第三阶段已确认设计](../designs/2026-09-05-query-execution-contracts-design.md)

## Global Constraints

- 生产变更限定于 `AbstractElasticsearchQueryBackend.kt`、`AbstractMongoQueryBackend.kt`、`MongoSnapshotQueryBackend.kt`、`MongoEventStreamQueryBackend.kt` 四个现有文件。
- 保留 `protected toObjectNode(Document)` 扩展点及所有公开方法、构造器和接口。不新增公开 API、依赖、模块、配置或通用转换框架。
- Query JSON、Schema HTTP、生成 OpenAPI/schema、Cursor token、排序、分页大小、存储布局及版本号不变。Gateway 的过滤、ABAC、脱敏、错误处理与 Schema 快照机制保持不变。
- 保持过滤/排序编译、SearchRequest 构建及 token 校验的既有时机；每次订阅独立调用客户端，不缓存 Mono、Future 或响应。
- 通用 `Documents.replacePrimaryKeyTo`、`toSnapshot`、`toMaterializedSnapshot`、`toDomainEventStream` 及其调用者保持不变，存储对象缺失主键时仍失败。
- Mongo 查询中 `_id` 缺席时保留缺席；存在但为 null 或错误类型时保留原异常，不强制转字符串。
- 不修改 PIT 或聚合执行器，也不重开前两阶段的解析性能优化。
- 不通过新增 Detekt suppression 或测试基类规避类大小问题。`detekt` 会自动修正格式，其产生的改动必须纳入检查与提交。
- 本阶段的收益是修复执行合同，不提出吞吐、延迟或分配量改善比例，也不重复前两阶段 JMH。
- 验证产物保存在忽略目录 `build/query-execution/`，不提交生成输出。已通过且对应代码未变化的验证不重复执行。

## 文件与执行次序

现有隔离 worktree：`/Users/ahoo/.codex/worktrees/b9ae/Wow`，应用管理的 detached HEAD。阶段设计提交 `beec77169`；生产基线 `ea89fadc3`。当前源码与第二阶段验证相同，无需重复建立功能基线；两个缺陷的既有复现记录位于 `build/query-execution/phase3-assessment.md`。

| 任务 | 文件职责 |
|---|---|
| 1 | ES 基类的一处 I/O 边界修复；新增聚焦游标订阅单测 |
| 2 | Mongo 基类的 internal 查询映射与两个内建调用者；新增聚焦投影结果单测 |
| 3 | 扩展四个既有真实后端集成测试文件；汇总验证并更新本计划与设计 |

用户已确认书面设计并选择子代理逐项实施与独立审查。按任务顺序执行，任务之间不再请求用户确认；控制器使用本计划独立的 SDD ledger，记录各任务开始提交、测试与审查结论。完整阶段最终审查范围为 `beec77169..HEAD`。保留当前工作区，不自行合并、推送或发布。

### Task 1: ES 游标每次订阅独立搜索

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackend.kt` (`executeCursor`)
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchCursorSubscriptionTest.kt`

**Interfaces:**
- Consumes: `ElasticsearchSnapshotQueryBackend(namedAggregate, elasticsearchClient)`；`QueryModelSchema.resolve(ICursorQuery)`；`ReactiveElasticsearchClient.search(SearchRequest, Class<ObjectNode>): Mono<ResponseBody<ObjectNode>>`。
- Produces: 同签名 `QueryBackend.cursor(ResolvedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>>`；每次订阅独立客户端调用。Task 3 复用这一既有接口进行真实查询。

- [x] **Step 1: 创建聚焦单测及真实 Backend fixture。** 使用仓库版权头、显式 imports、FluentAssert；在新测试类中准备以下 fixture，避免扩大旧综合测试类或抽出测试框架：

```kotlin
private val client = mockk<ReactiveElasticsearchClient>()
private val backend = ElasticsearchSnapshotQueryBackend(MaterializedNamedAggregate("test", "cursor"), client)
private val id = QueryField("aggregateId")
private val schema = QueryModelSchema(
    QueryModel.SNAPSHOT,
    emptySet(),
    mapOf(
        id to QueryFieldSchema(
            title = null,
            description = null,
            enumValues = null,
            valueTypes = setOf(QueryValueType.STRING),
            nullable = false,
            required = true,
            cardinality = QueryCardinality.SINGLE,
            semanticType = null,
            dynamicChildren = false,
            bindings = mapOf(QueryCapability.SORT to QueryFieldBinding(id, id, null)),
            rewriteMode = QueryRewriteMode.NONE,
        ),
    ),
)

private fun resolved(cursor: String? = null): ResolvedQuery<ICursorQuery> = ResolvedQuery(
    schema.resolve(CursorQuery(MatchAllFilter, size = 1, cursor = cursor))
        .requireAccepted(QuerySchemaValidationMode.STRICT),
    schema,
)

private fun response(): ResponseBody<ObjectNode> = SearchResponse.of<ObjectNode> { response ->
    response.took(1).timedOut(false)
        .shards { it.failed(0).successful(1).total(1) }
        .hits { hits ->
            hits.hits { hit ->
                hit.index(backend.indexName).id("id-1")
                    .source(JsonNodeFactory.instance.objectNode().put("aggregateId", "id-1"))
                    .sort(listOf(FieldValue.of("id-1")))
            }
        }
}
```

客户端 stub 每次调用时创建 Future，不能由 stub 自己延迟创建响应。最小 RED 合同如下：

```kotlin
@Test
fun `cursor should defer search and create fresh results when repeated`() {
    every { client.search(any<SearchRequest>(), ObjectNode::class.java) } answers {
        Mono.fromFuture(CompletableFuture.completedFuture(response()))
    }
    val publisher = backend.cursor(resolved())
    verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }

    val nodes = publisher.map { it.list.single() }
        .repeat(1)
        .index()
        .doOnNext { indexed -> if (indexed.t1 == 0L) indexed.t2.put("mutated", true) }
        .map { it.t2 }
        .collectList().block()!!
    nodes.assert().hasSize(2)
    nodes[1].assert().isNotSameAs(nodes[0])
    nodes[1].has("mutated").assert().isFalse()
    nodes.map { it.path("aggregateId").asString() }.assert().containsExactly("id-1", "id-1")
    verify(exactly = 2) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
}
```

- [x] **Step 2: 运行 RED。**

```bash
./gradlew :wow-elasticsearch:test --tests 'me.ahoo.wow.elasticsearch.query.ElasticsearchCursorSubscriptionTest' --console=plain
```

预期因为创建 Publisher 已调用一次客户端而失败；记录命令、具体失败与实际请求次数。fixture 的编译错误不算 RED。

- [x] **Step 3: 最小生产修复。** `executeCursor` 采用以下结构，其余代码不动：

```kotlin
internal fun executeCursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>> {
    val compiled = compile(query.filter, query.sort, schema)
    val request = cursorSearchRequest(query, compiled, schema)
    return Mono.defer { elasticsearchClient.search(request, ObjectNode::class.java) }
        .map { response -> response.toCursorPage(query) }
}
```

- [x] **Step 4: 补齐 retry、并发/取消及校验时机合同。** 使用 Step 1 fixture 写入以下具体用例，保持所有变化在同一新测试类：

```kotlin
@Test
fun `cursor retry should issue a new request after request failure`() {
    val failure = IllegalStateException("first-request")
    var calls = 0
    every { client.search(any<SearchRequest>(), ObjectNode::class.java) } answers {
        val future = CompletableFuture<ResponseBody<ObjectNode>>()
        if (calls++ == 0) future.completeExceptionally(failure) else future.complete(response())
        Mono.fromFuture(future)
    }
    backend.cursor(resolved()).retry(1).test()
        .assertNext { it.list.single().path("aggregateId").asString().assert().isEqualTo("id-1") }
        .verifyComplete()
    calls.assert().isEqualTo(2)
}

@Test
fun `cursor retry should discard a downstream mutation`() {
    every { client.search(any<SearchRequest>(), ObjectNode::class.java) } answers {
        Mono.fromFuture(CompletableFuture.completedFuture(response()))
    }
    val seen = mutableListOf<ObjectNode>()
    backend.cursor(resolved()).map { it.list.single() }
        .doOnNext { node ->
            seen += node
            if (seen.size == 1) {
                node.put("mutated", true)
                error("retry-once")
            }
        }.retry(1).test()
        .assertNext { node ->
            node.assert().isNotSameAs(seen.first())
            node.has("mutated").assert().isFalse()
        }.verifyComplete()
    verify(exactly = 2) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
}

@Test
fun `cancelling one concurrent cursor subscription should not cancel another`() {
    val futures = mutableListOf<CompletableFuture<ResponseBody<ObjectNode>>>()
    every { client.search(any<SearchRequest>(), ObjectNode::class.java) } answers {
        val future = CompletableFuture<ResponseBody<ObjectNode>>().also(futures::add)
        Mono.fromFuture(future)
    }
    val publisher = backend.cursor(resolved())
    val first = publisher.subscribe()
    val nodes = mutableListOf<ObjectNode>()
    val failures = mutableListOf<Throwable>()
    publisher.subscribe({ nodes += it.list.single() }, failures::add)
    futures.assert().hasSize(2)
    first.dispose()
    futures[0].isCancelled.assert().isTrue()
    futures[1].isCancelled.assert().isFalse()
    futures[1].complete(response())
    failures.assert().isEmpty()
    nodes.single().path("aggregateId").asString().assert().isEqualTo("id-1")
}

@Test
fun `invalid cursor should still fail while assembling the request`() {
    assertThrows<IllegalArgumentException> { backend.cursor(resolved("invalid!")) }
        .message.assert().isEqualTo("Invalid cursor.")
    verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
}
```

并发用例让两个订阅同时处于未完成状态，无 sleep、线程调度猜测或新资源管理器。保留现有 `AbstractElasticsearchQueryBackendTest` 对 size+1、source、sort、token 和响应 arity 的断言。

- [x] **Step 5: GREEN、相关回归和静态检查后提交。** 先用 Step 2 命令确认新测试通过，再执行：

```bash
./gradlew :wow-elasticsearch:detekt :wow-elasticsearch:test --tests 'me.ahoo.wow.elasticsearch.query.*' --console=plain
git diff --check
```

如 Detekt 自动改动代码，检查其 diff，并对实际变化补跑覆盖测试。提交两个文件，信息 `fix(elasticsearch): isolate cursor subscriptions`；完整报告含 RED/GREEN、相关测试数量、日志、提交及自审。真实后端验证由 Task 3 补齐。

### Task 2: Mongo 查询结果允许主键投影缺席

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackend.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoQueryProjectionResultTest.kt`

**Interfaces:**
- Consumes: `Documents.replacePrimaryKeyTo(String): Document` 与既有 `Document.toObjectNode(): ObjectNode`。
- Produces: internal `Document.toQueryObjectNode(idField: String): ObjectNode`；两个内建 Backend 的既有 single/list/paged/cursor 返回接口不变。Task 3 使用这些接口验证真实投影。

- [x] **Step 1: 创建新测试类，覆盖两模型和三种查询。** 使用实际 `MongoSnapshotQueryBackend` / `MongoEventStreamQueryBackend` 和 MockK `MongoCollection<Document>`。以 `@CsvSource` 给出六组模型、逻辑主键与操作：

```kotlin
@ParameterizedTest
@CsvSource(
    "SNAPSHOT,aggregateId,single",
    "SNAPSHOT,aggregateId,list",
    "SNAPSHOT,aggregateId,paged",
    "EVENT_STREAM,id,single",
    "EVENT_STREAM,id,list",
    "EVENT_STREAM,id,paged",
)
fun `query should allow projected identity to be absent`(modelName: String, logicalId: String, operation: String) {
    val model = QueryModel(modelName)
    val backend = backend(model)
    val schema = schema(model, logicalId)
    val projection = Projection(exclude = listOf(QueryField(logicalId)))
    arrange { Document("value", "visible") }
    val result = when (operation) {
        "single" -> backend.single(ResolvedQuery(
            schema.resolve(SingleQuery(MatchAllFilter, projection)).requireAccepted(QuerySchemaValidationMode.STRICT), schema,
        )).map(::listOf)
        "list" -> backend.list(ResolvedQuery(
            schema.resolve(ListQuery(MatchAllFilter, projection, limit = 1)).requireAccepted(QuerySchemaValidationMode.STRICT), schema,
        )).collectList()
        "paged" -> backend.paged(ResolvedQuery(
            schema.resolve(PagedQuery(MatchAllFilter, projection, pagination = Pagination(size = 1)))
                .requireAccepted(QuerySchemaValidationMode.STRICT), schema,
        )).map { page ->
            page.total.assert().isEqualTo(7L)
            page.list
        }
        else -> error(operation)
    }
    result.test().assertNext { nodes ->
        val node = nodes.single()
        node.path("value").asString().assert().isEqualTo("visible")
        node.has(logicalId).assert().isFalse()
        node.has("_id").assert().isFalse()
    }.verifyComplete()
}
```

fixture 采用以下结构；格式遵循 Kotlin 多行参数规范：

```kotlin
private val collection = mockk<MongoCollection<Document>>()
private val namedAggregate = MaterializedNamedAggregate("test", "projection")

private fun backend(model: QueryModel): QueryBackend = if (model == QueryModel.SNAPSHOT) {
    MongoSnapshotQueryBackend(namedAggregate, collection)
} else {
    MongoEventStreamQueryBackend(namedAggregate, collection)
}

private fun schema(model: QueryModel, logicalId: String): QueryModelSchema {
    val logical = QueryField(logicalId)
    val physical = QueryField("_id")
    return QueryModelSchema(model, emptySet(), mapOf(logical to QueryFieldSchema(
        title = null,
        description = null,
        enumValues = null,
        valueTypes = setOf(QueryValueType.STRING),
        nullable = false,
        required = true,
        cardinality = QueryCardinality.SINGLE,
        semanticType = null,
        dynamicChildren = false,
        bindings = mapOf(QueryCapability.PRESENCE to QueryFieldBinding(logical, physical, null)),
        projectionField = physical,
        rewriteMode = QueryRewriteMode.NONE,
    )))
}

private fun arrange(document: () -> Document) {
    val publisher = mockk<FindPublisher<Document>>()
    every { collection.find(any<Bson>()) } returns publisher
    every { collection.countDocuments(any<Bson>()) } returns Mono.just(7L)
    every { publisher.projection(any()) } returns publisher
    every { publisher.sort(any()) } returns publisher
    every { publisher.skip(any()) } returns publisher
    every { publisher.limit(any()) } returns publisher
    every { publisher.batchSize(any()) } returns publisher
    every { publisher.first() } returns publisher
    every { publisher.subscribe(any()) } answers {
        Flux.just(document()).subscribe(firstArg<Subscriber<in Document>>())
    }
}
```

用一个捕获的 projection BSON 断言真实排除字段为 `_id`，不要只依赖假定输入。Doc supplier 每次订阅创建独立可变 Document；不通过复用已被重命名的 Document 验证驱动行为。

- [x] **Step 2: 运行 RED。**

```bash
./gradlew :wow-mongo:test --tests 'me.ahoo.wow.mongo.query.MongoQueryProjectionResultTest' --console=plain
```

预期六种合法投影均在严格主键重命名处失败；记录具体异常与命令。修好 fixture/编译错误后才记录 RED。

- [x] **Step 3: 添加查询专用 helper 并接入两个内建 Backend。** 在 `AbstractMongoQueryBackend.kt` 的类外增加以下 internal 函数，导入现有 `Documents.replacePrimaryKeyTo`：

```kotlin
internal fun Document.toQueryObjectNode(idField: String): ObjectNode {
    if (containsKey(Documents.ID_FIELD)) {
        replacePrimaryKeyTo(idField)
    }
    return toObjectNode()
}
```

两个 override 分别改为：

```kotlin
// MongoSnapshotQueryBackend
override fun toObjectNode(document: Document): ObjectNode =
    document.toQueryObjectNode(MessageRecords.AGGREGATE_ID)

// MongoEventStreamQueryBackend
override fun toObjectNode(document: Document): ObjectNode =
    document.toQueryObjectNode(MessageRecords.ID)
```

更新各自 imports，不改通用 Documents、存储转换、聚合行映射、protected 扩展点、游标 token 提取或隐藏字段清理。

- [x] **Step 4: 锁住缺席、非法值与持久化边界。** 在同一新测试类中使用两个模型的实际 Backend 执行 single。复用 `arrange`、`schema`，将每组输入和断言落实为参数化测试或少量聚焦测试：

```kotlin
@ParameterizedTest
@CsvSource("SNAPSHOT,aggregateId", "EVENT_STREAM,id")
fun `query identity mapping should preserve strict values and existing fields`(modelName: String, logicalId: String) {
    val model = QueryModel(modelName)
    fun single(document: () -> Document): Mono<ObjectNode> {
        arrange(document)
        return backend(model).single(ResolvedQuery(SingleQuery(MatchAllFilter), schema(model, logicalId)))
    }

    single { Document("_id", "storage-id").append(logicalId, "old-id").append("value", "visible") }
        .test().assertNext { node ->
            node.path(logicalId).asString().assert().isEqualTo("storage-id")
            node.has("_id").assert().isFalse()
        }.verifyComplete()
    single { Document(logicalId, "existing-id").append("value", "visible") }
        .test().assertNext { it.path(logicalId).asString().assert().isEqualTo("existing-id") }.verifyComplete()
    single { Document("_id", null) }.test().verifyError(IllegalStateException::class.java)
    single { Document("_id", 42) }.test().verifyError(ClassCastException::class.java)
    assertThrows<IllegalStateException> { Document("value", "visible").replacePrimaryKeyTo(logicalId) }
}
```

再复用同一实际 Backend.single Publisher，修改首次返回节点后重新订阅，断言第二次节点独立、值未改变；Doc supplier 每次产生新 Document。此处验证既有结果所有权，不增加生产复制逻辑。运行既有 `MongoCursorDocumentsTest`、`AbstractMongoQueryBackendTest` 及 `DocumentsKtTest`，覆盖隐藏主键、token 与规范 JSON。

- [x] **Step 5: GREEN、相关回归与 Detekt 后提交。** 先运行 Step 2 的聚焦命令，再执行：

```bash
./gradlew :wow-mongo:detekt :wow-mongo:test --tests 'me.ahoo.wow.mongo.query.*' --tests 'me.ahoo.wow.mongo.DocumentsKtTest' --console=plain
git diff --check
```

检查自动格式化结果，只提交四个任务文件，信息 `fix(mongo): preserve projected query identity absence`。报告 RED/GREEN、相关测试数量、严格转换对照、日志和自审。真实数据库投影由 Task 3 验证。

### Task 3: 真实后端合同回归与交付记录

**Files:**
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackendTest.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryBackendTest.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackendTest.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackendTest.kt`
- Modify: 本计划与 `documentation/designs/2026-09-05-query-execution-contracts-design.md`

**Interfaces:**
- Consumes: 既有 `queryBackendBinding.schemaProvider.schema()`、`schema.resolve(...)`、`QueryBackend.cursor/single/list/paged(ResolvedQuery)`；Task 1/2 只修正这些接口的内部行为。
- Produces: 两模型的实际 ES 游标隔离、Mongo 主键排除投影结果证据及最终验证记录。

- [x] **Step 1: ES Snapshot 加入实际重复订阅合同。** 在既有 snapshot fixture 上执行下面的测试体，使用当前文件已有 `filterExpression`、`snapshotQueryBackend` 和 `snapshot`；不经过 Gateway：

```kotlin
val schema = queryBackendBinding.schemaProvider.schema().block()!!
val query = CursorQuery(filterExpression { "aggregateId" eq snapshot.aggregateId.id }, size = 1)
val publisher = snapshotQueryBackend.cursor(
    ResolvedQuery(schema.resolve(query).requireAccepted(QuerySchemaValidationMode.STRICT), schema),
)
publisher.map { it.list.single() }.repeat(1).index()
    .doOnNext { indexed -> if (indexed.t1 == 0L) indexed.t2.put("mutated", true) }
    .map { it.t2 }.collectList().test()
    .assertNext { nodes ->
        nodes.assert().hasSize(2)
        nodes[1].assert().isNotSameAs(nodes[0])
        nodes[1].has("mutated").assert().isFalse()
        nodes.map { it.path("aggregateId").asString() }.assert()
            .containsExactly(snapshot.aggregateId.id, snapshot.aggregateId.id)
    }.verifyComplete()
```

- [x] **Step 2: ES EventStream 加入实际修改后 retry 合同。** 复用该文件现有 store、factory 与 `generateEventStream`：

```kotlin
val stream = generateEventStream(namedAggregate.aggregateId(generateGlobalId()))
eventStore.append(stream).block()
val schema = queryBackendBinding.schemaProvider.schema().block()!!
val query = CursorQuery(filterExpression { id(stream.id) }, size = 1)
val publisher = queryBackendBinding.backend.cursor(
    ResolvedQuery(schema.resolve(query).requireAccepted(QuerySchemaValidationMode.STRICT), schema),
)
val seen = mutableListOf<ObjectNode>()
publisher.map { it.list.single() }.doOnNext { node ->
    seen += node
    if (seen.size == 1) {
        node.put("mutated", true)
        error("retry-once")
    }
}.retry(1).test().assertNext { node ->
    seen.assert().hasSize(2)
    node.assert().isNotSameAs(seen.first())
    node.has("mutated").assert().isFalse()
    node.path("id").asString().assert().isEqualTo(stream.id)
}.verifyComplete()
```

测试调用一次 Backend.cursor 后复用 Publisher；不能把每次调用藏入额外 Mono.defer 来让旧实现也通过。

- [x] **Step 3: 在两个 Mongo 集成类覆盖三种投影查询。** Snapshot 测试首先初始化：

```kotlin
val logicalId = "aggregateId"
val filter = filterExpression { id(snapshot.aggregateId.id) }
val payloadField = "state"
```

EventStream 测试首先初始化：

```kotlin
val stream = generateEventStream(
    namedAggregate.aggregateId(generateGlobalId()),
    eventCount = 1,
    createdEventSupplier = { MockAggregateCreated("projected-event") },
)
eventStore.append(stream).block()
val logicalId = "id"
val filter = filterExpression { id(stream.id) }
val payloadField = "body"
```

每个测试体继续使用下面的结构：

```kotlin
val schema = queryBackendBinding.schemaProvider.schema().block()!!
val projection = Projection(exclude = listOf(QueryField(logicalId)))
val backend = queryBackendBinding.backend
val single = SingleQuery(filter, projection)
val list = ListQuery(filter, projection, limit = 1)
val paged = PagedQuery(filter, projection, pagination = Pagination(size = 1))
val results = listOf(
    backend.single(ResolvedQuery(schema.resolve(single).requireAccepted(QuerySchemaValidationMode.STRICT), schema))
        .map(::listOf),
    backend.list(ResolvedQuery(schema.resolve(list).requireAccepted(QuerySchemaValidationMode.STRICT), schema))
        .collectList(),
    backend.paged(ResolvedQuery(schema.resolve(paged).requireAccepted(QuerySchemaValidationMode.STRICT), schema))
        .map { page ->
            page.total.assert().isEqualTo(1L)
            page.list
        },
)
results.forEach { result ->
    result.test().assertNext { nodes ->
        val node = nodes.single()
        node.has(logicalId).assert().isFalse()
        node.has("_id").assert().isFalse()
        node.has(payloadField).assert().isTrue()
    }.verifyComplete()
}
```

每个结果再使用该模型的具体 payload 断言。Snapshot：

```kotlin
node.path("state").isObject.assert().isTrue()
node.path("state").path("id").asString().assert().isEqualTo(snapshot.aggregateId.id)
```

EventStream：

```kotlin
node.path("body").isArray.assert().isTrue()
node.path("body").size().assert().isEqualTo(1)
node.path("body").path(0).path("body").path("data").asString().assert().isEqualTo("projected-event")
```

两个模型都需验证三种接口，保留已有普通投影与游标用例。

- [x] **Step 4: 运行两后端查询集成回归。**

```bash
./gradlew :wow-elasticsearch:integrationTest --tests 'me.ahoo.wow.elasticsearch.query.*' :wow-mongo:integrationTest --tests 'me.ahoo.wow.mongo.query.*' --console=plain
git diff --check
```

记录完整日志和两模块 XML 的 tests/failures/errors/skipped；测试应实际执行，环境问题与实现失败分开报告。新集成合同是 Task 1/2 修复的真实后端验证；其最初 RED 已由单元合同证明，不为重复制造 RED 临时切换生产实现。

- [x] **Step 5: 汇总已有验证证据并更新文档。** 引用 Task 1/2 对各自最终源码的单测、Detekt 和 RED/GREEN，避免重复执行未变化的测试；本任务只增加集成测试和文档。校验版本范围中生产文件只有设计允许的四个，`Documents.kt`、Gateway、Schema、PIT、聚合编译/执行器均未变。

在两份文档写明：已修复的具体触发方式、最终行为、实际验证命令和测试数、严格转换及公共合同保留范围；不宣称性能提升。更新已完成步骤，不将缓存检查计作本轮实际执行。日志保存在 `build/query-execution/`。用脚本检查 Markdown 本地链接、围栏与占位词，运行 `git diff --check`；提交六个任务文件，信息 `test(query): verify backend execution contracts`。报告精确提交和所有检查结果。

## 实施结果

Task 1 以 `184375bb7` 完成。原实现创建合法游标 Publisher 时就调用 ES 客户端，导致同一 Publisher 的 repeat、retry 和并发订阅共享 Future、响应与可变节点；修复后请求仍在既有编译、SearchRequest 构建和 token 校验之后确定，但客户端搜索在每次订阅时独立执行。聚焦测试 RED 时 5 项中 4 项按预期失败，GREEN 后 5/5 通过；最终 ES 查询单测与 Detekt 通过，XML 统计为 150 tests、0 failures、0 errors、0 skipped。

Task 2 以 `150372b28` 完成。Mongo 原生投影把 Snapshot `aggregateId` 或 EventStream `id` 排除编译为 `{ "_id": 0 }`，旧查询映射随后因严格主键重命名失败；查询专用转换现在只在 `_id` 实际存在时调用原严格函数，缺席时保留缺席。聚焦测试 RED 为 10 项中 8 项按预期失败，GREEN 后 10/10 通过；最终 Mongo 查询与 `DocumentsKtTest`、Detekt 通过，XML 统计为 186 tests、0 failures、0 errors、0 skipped。`Documents.replacePrimaryKeyTo` 与全部存储转换仍保持缺失主键即失败。

Task 3 在四个既有 Testcontainers fixture 中新增 4 个真实后端合同：ES Snapshot 对同一 Backend cursor Publisher 执行 repeat，ES EventStream 在修改首次节点后对同一 Publisher retry；Mongo Snapshot 与 EventStream 各自通过 strict Schema 对 single/list/paged 执行逻辑主键排除，并验证 payload，paged 同时验证 `total = 1`。本轮实际运行：

```bash
./gradlew :wow-elasticsearch:integrationTest --tests 'me.ahoo.wow.elasticsearch.query.*' :wow-mongo:integrationTest --tests 'me.ahoo.wow.mongo.query.*' --console=plain
```

结果为 BUILD SUCCESSFUL：ES 95 tests、Mongo 87 tests，合计 182 tests、0 failures、0 errors、0 skipped。Task 1/2 已通过且源码未再变化的单测和 Detekt 未重复执行，本轮不把 Gradle 的 UP-TO-DATE 任务计作实际验证。完整日志保存在忽略目录 `build/query-execution/task-3-integration.log`。

阶段生产改动仅为设计列出的四个文件；公共 API 与 `protected toObjectNode(Document)` 扩展点保持不变。`Documents.kt`、Gateway、Schema、PIT、聚合编译/执行器、Query JSON、Cursor token、存储布局及生成合同均未修改。本阶段只证明执行合同修复，不作性能提升声明。

任务结束后控制器进行一次覆盖 `beec77169..HEAD` 的最终审查。审查记录归档后保留当前工作区与提交，不执行合并、推送或发布。
