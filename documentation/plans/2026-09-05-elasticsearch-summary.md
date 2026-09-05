# Elasticsearch Single-Search Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ES 无分组汇总通过一次严格搜索完成，减少两次 PIT 客户端调用，并拒绝部分汇总。

**Architecture:** 在现有 Pager 的 `execute` 中先区分汇总与分组；私有搜索方法以可空 PIT Session 复用原请求构建和响应转换。无分组使用原索引名及 `allowPartialSearchResults(false)`，分组继续使用原 PIT 生命周期。

**Tech Stack:** Kotlin、Reactor、Spring Data Elasticsearch 6.1.1、Elasticsearch Java Client 9.4.5；JUnit、MockK、FluentAssert；现有 Testcontainers ES 9.2.6。

**Spec:** [已批准设计](../designs/2026-09-05-elasticsearch-summary-design.md)。阶段起点 `26fb4ac9c`，设计提交 `f67e24544`。

## Global Constraints

- 生产改动仅限 `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt`。
- 无分组汇总显式设置 `allowPartialSearchResults(false)`。用户已接受该失败行为收紧；不扩展到分组分页、普通查询或 MongoDB。
- 使用原 `indexName`，不把 Schema mapping 解析得到的物理索引名替换进搜索请求。
- 请求构建与 `client.search` 继续在 `Mono.defer` 中。未订阅时不发送请求；每次订阅、repeat 或 retry 独立发起搜索并产生结果，不复用 Future、响应或可变 JSON 节点。
- 保留构造器现有 `batchSize` 与 `keepAlive` 校验。分组请求的部分结果参数保持未显式指定；保留最新 PIT ID、afterKey、排序及完成、错误、取消释放逻辑。
- 复用原 rootQuery、runtime mappings、聚合构建与结果归一化；成功的空汇总仍返回 COUNT=0、ANY=null、数值指标=null；失败不转换为空汇总。
- 不改 Gateway、Schema 准入、权限/脱敏、编译器、Query AST、Spring wiring、MongoDB、存储布局、公开 API、生成协议、依赖或版本号。
- 不新增测试框架、公共 fixture、Detekt suppression 或执行服务。测试使用现有 FluentAssert `.assert()`。
- 失败注入仅作用于测试创建的资源，不修改集群级设置或已有索引；资源由 fixture 或 finally 清理，状态等待必须有界。
- 性能验收只报告正常单次订阅的客户端调用数 3→1，不包含 Schema、内部重试、分片通信或调用方 retry；不新增 JMH 矩阵或延迟百分比结论。
- 日志与评审产物放在忽略目录 `build/query-summary/`；文档使用 `documentation/designs/` 与 `documentation/plans/`。保留当前隔离 worktree，本地提交，不 push、merge 或发布。

## 文件职责与顺序

| 文件 | 职责 | Task |
|---|---|---|
| `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt` | 唯一生产改动：汇总分支与共享请求目标 | 1 |
| `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPagerTest.kt` | 加强原空汇总、分组对照 | 1 |
| `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchSummaryExecutionTest.kt` | 独立订阅、错误、取消与嵌套请求合同 | 1 |
| `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchSummaryExecutionIntegrationTest.kt` | 三个真实原生边界 | 2 |
| `documentation/docs/{zh,en}/guide/query/aggregation-query.md` | 向使用者说明成功空输入与 ES 部分失败 | 2 |
| 本计划、对应设计 | 执行记录、验证结果与证据范围 | 2 |

两个任务顺序执行，各自完成实现、自审、本地提交及独立审查。已完成的前三阶段不重新执行。Task 1 的单测验证在 Task 2 未改其源码时直接作为最终证据。

### Task 1: 单次严格搜索与响应式合同

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPagerTest.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchSummaryExecutionTest.kt`

**Interfaces:**
- Consumes: `ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(query: AggregationQuery, schema: QueryModelSchema): ElasticsearchAggregationPlan`；`ElasticsearchPointInTime.use((Session) -> Publisher<T>): Flux<T>`；现有 `summary(plan)` 转换。
- Produces: 保持 `ElasticsearchAggregationPager(client, indexName, batchSize, keepAlive).execute(plan): Flux<ObjectNode>`。无分组无 PIT、严格搜索；分组原合同。Task 2 通过这一个生产入口验证原生行为。

- [ ] **Step 1: 在现有空汇总测试中建立 RED。** 保留已有 `summaryResponse()` 与 PIT 桩以让旧实现完整执行，再捕获搜索请求，增加以下断言。把 Publisher 保存为变量，在订阅之前断言零客户端调用；现有 count/total 的输出断言继续保留。

```kotlin
val request = slot<SearchRequest>()
every { client.search(capture(request), Map::class.java) } returns Mono.just(summaryResponse())
val result = pager().execute(plan)
verify(exactly = 0) { client.search(any<SearchRequest>(), Map::class.java) }
verify(exactly = 0) { client.openPointInTime(any<OpenPointInTimeRequest>()) }
result.test().assertNext {
    it.path("count").longValue().assert().isEqualTo(0L)
    it.path("total").isNull.assert().isTrue()
}.verifyComplete()
request.captured.index().assert().containsExactly("test-index")
request.captured.pit().assert().isNull()
request.captured.allowPartialSearchResults().assert().isEqualTo(false)
request.captured.size().assert().isEqualTo(0)
request.captured.trackTotalHits()!!.enabled().assert().isFalse()
request.captured.query().assert().isEqualTo(plan.rootQuery)
verify(exactly = 1) { client.search(any<SearchRequest>(), Map::class.java) }
verify(exactly = 0) { client.openPointInTime(any<OpenPointInTimeRequest>()) }
verify(exactly = 0) { client.closePointInTime(any<ClosePointInTimeRequest>()) }
```

- [ ] **Step 2: 运行 RED，保存真实失败输出。**

```bash
./gradlew :wow-elasticsearch:test --tests '*ElasticsearchAggregationPagerTest.summary should request once and normalize empty values' --console=plain
```

预期旧实现请求 index 为空、含 PIT，或 PIT 调用数断言失败；编译错误不算 RED。日志保存 `build/query-summary/task-1-red.log`。

- [ ] **Step 3: 修改唯一生产文件。** 构造器改成 `private val indexName: String`，保持原校验及 `pointInTime` 初始化。替换执行分支：

```kotlin
fun execute(plan: ElasticsearchAggregationPlan): Flux<ObjectNode> {
    if (plan.groupSources.isEmpty()) {
        return search(plan, null, afterKey = emptyMap(), pageSize = 0)
            .map { response -> response.summary(plan) }
            .flux()
    }
    return pointInTime.use { pit -> grouped(plan, pit) }
}
```

`search` 的 PIT 参数改为 `ElasticsearchPointInTime.Session?`。复用当前构造器，将原 PIT 设置移动到唯一条件分支中，其余聚合和查询设置保持原样：

```kotlin
val request = SearchRequest.of {
    it.query(plan.rootQuery)
        .size(0)
        .trackTotalHits { track -> track.enabled(false) }
        .runtimeMappings(plan.runtimeMappings)
        .aggregations(ROOT_AGGREGATION, plan.aggregation(afterKey, pageSize))
        .apply {
            if (pit == null) {
                index(indexName).allowPartialSearchResults(false)
            } else {
                pit { pointInTime ->
                    pointInTime.id(pit.id).keepAlive { keepAlive -> keepAlive.time(this@ElasticsearchAggregationPager.pointInTime.keepAliveValue) }
                }
            }
        }
}
```

请求和 `client.search(request, Map::class.java)` 仍全部位于原 `Mono.defer` 内，末尾改为 `.doOnNext { pit?.update(it.pitId()) }`。不改其他方法。

- [ ] **Step 4: 添加聚焦执行测试，并加强原分组对照。** 新类使用实际编译器、空 `QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())`、MockK 客户端和最小响应构造；不要复制现有大分组 fixture。测试使用 `SearchResponse.of<Map<*, *>>`，必须设置 took、timedOut、shards、hits 与 `__wow_aggregation`，汇总响应不设置 PIT ID。

订阅/失败核心检查如下；`plan` 使用 `aggregation { count("count") }` 编译，`response(count)` 的根 filter 聚合设置相应 docCount。桩返回已创建的 Mono，不在桩内放 `Mono.defer` 掩盖生产问题。

```kotlin
every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
    Mono.just(response(1)), Mono.just(response(2)),
)
val result = ElasticsearchAggregationPager(client, "summary-alias").execute(plan)
verify(exactly = 0) { client.search(any<SearchRequest>(), Map::class.java) }
val rows = result.repeat(1).collectList().block()!!
rows.map { it.path("count").longValue() }.assert().containsExactly(1L, 2L)
rows[0].assert().isNotSameAs(rows[1])
verify(exactly = 2) { client.search(any<SearchRequest>(), Map::class.java) }
```

独立失败测试用 `Mono.error<ResponseBody<Map<*, *>>>(failure)` 并 `.expectErrorMatches { it === failure }`；retry 测试先失败、后返回 count=2，用同一个 Publisher `.retry(1)` 断言只产生成功行且两次调用。取消用 `CompletableFuture<ResponseBody<Map<*, *>>>()` 及 `Mono.fromFuture(future)`，`.thenCancel().verify()` 后断言 `future.isCancelled`。各条路径均验证 open/close PIT 零调用。

再加一个嵌套非空汇总：通过 `aggregation { expand("state.items"); count("count"); sum(field("amount") * constant(2.0), "total") }` 编译；响应为根 nested → `__wow_element_filter_0` filter，docCount=3，total.sum=12，`__wow_value_count_total`.valueCount=3。结果断言 count=3、total=12。捕获请求断言 rootQuery、runtimeMappings 与编译计划相同，nested path/filter/指标字段及贡献计数聚合正确，size=0、无 PIT、原 alias、严格搜索。复用原测试与 Task 2 TCK 覆盖其他指标/空值组合，不复制归一化算法。

原 `group sort should pass composite after key and close latest pit` 增加：

```kotlin
requests.forEach { request ->
    request.index().assert().isEmpty()
    request.allowPartialSearchResults().assert().isNull()
    request.pit().assert().isNotNull()
}
requests[0].pit()!!.id().assert().isEqualTo("pit-1")
```

保留既有第二页 afterKey、pit-2 和最终关闭 pit-3 断言；已有 PIT 错误/取消测试无需新增重复测试。

- [ ] **Step 5: 运行聚焦检查后执行相关单测与 Detekt。**

```bash
./gradlew :wow-elasticsearch:test --tests '*ElasticsearchAggregationPagerTest' --tests '*ElasticsearchSummaryExecutionTest' --console=plain
./gradlew :wow-elasticsearch:test --tests 'me.ahoo.wow.elasticsearch.query.*' :wow-elasticsearch:detekt --console=plain
git diff --check
```

先迭代聚焦类，再只跑一次相关查询范围。Detekt 在当前仓库开启自动修正；如有改写，审查差异并补跑必要检查。保存 GREEN 日志和 XML 计数至 `build/query-summary/`，输出调用计数证据。

- [ ] **Step 6: 自审并提交。** 提交上列三个文件，message `perf(elasticsearch): execute summaries with one strict search`。报告包括 RED/GREEN、命令、测试数、是否有格式化改动，以及源码只改一个 Pager 的验证。独立审查通过后进入 Task 2。

### Task 2: 真实 ES 故障、别名合同与使用文档

**Files:**
- Create: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchSummaryExecutionIntegrationTest.kt`
- Modify: `documentation/docs/zh/guide/query/aggregation-query.md`
- Modify: `documentation/docs/en/guide/query/aggregation-query.md`
- Modify: `documentation/designs/2026-09-05-elasticsearch-summary-design.md`
- Modify: `documentation/plans/2026-09-05-elasticsearch-summary.md`

**Interfaces:**
- Consumes: Task 1 的 `ElasticsearchAggregationPager(...).execute(plan): Flux<ObjectNode>`；实际 `ElasticsearchAggregationCompiler.compile(query, schema)`；现有 `ElasticsearchTestFixture` 与 `ReactiveElasticsearchClients.createReactiveElasticsearchClient(fixture)`。
- Produces: 三项真实环境合同、双语失败语义及可追溯验证记录；不新增生产接口。

- [ ] **Step 1: 构造独立真实测试资源。** JUnit `@RegisterExtension` 注册 `ElasticsearchTestFixture`，通过现有 helper 获取受 fixture 管理的客户端。每个测试创建 `fixture.index("summary")` 唯一索引，两个 primary、零 replica、显式字段 `visible:boolean`、`fail:boolean`、`amount:double`。使用 `try/finally` 删除该索引及其 alias；fixture 关闭客户端。只在测试线程使用同步探测或 block。

原生路由/就绪探测可复用该客户端 transport，无需新 HTTP 层或连接：

```kotlin
val nativeClient = ElasticsearchClient(client._transport(), client._transportOptions())
val routes = (0..31).map { it.toString() }
val shard = nativeClient.searchShards { it.index(index).routing(route) }.shards().single().single()
```

在测试中读取 shard 的 state/编号，选择落在不同 primary 的两个 route。使用索引范围 health 等待或 `Mono.defer { ... }.repeatWhen { it.delayElements(Duration.ofMillis(100)) }.filter { ready }.next().block(Duration.ofSeconds(15))`；条件检查先确认实际分片状态，不用固定 sleep。探测 wrapper 共用 transport，不单独关闭它；fixture 持有最终关闭权。可使用原生 client 进行 setup，实际被测搜索必须调用生产 Pager。

- [ ] **Step 2: 验证 alias filter 与 search routing。** 等待两个主分片 STARTED，取 routeA、routeB 指向不同 shard。创建 alias，filter=`term(visible=true)` 且 searchRouting=routeA。写入三条并 refresh：routeA/visible=true/amount=5，routeA/visible=false/amount=100，routeB/visible=true/amount=1000。

```kotlin
val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
    aggregation { count("count"); sum("amount", "total") },
    QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap()),
)
ElasticsearchAggregationPager(client, alias).execute(plan).test()
    .assertNext {
        it.path("count").longValue().assert().isEqualTo(1L)
        it.path("total").doubleValue().assert().isEqualTo(5.0)
    }.verifyComplete()
```

写入三条的 `_id` 必须不同；查询编译器默认根过滤需阅读确认，若要求 deleted 字段则在 fixture 文档显式写 `deleted=false`，不改变生产编译器。

- [ ] **Step 3: 验证一个不可用分片时整体失败。** 只在新索引 settings 中设置 `routing.allocation.total_shards_per_node=1`，create 的 `wait_for_active_shards=0`。当前 fixture 是单节点；有界等待一个 STARTED、一个 UNASSIGNED，向可用 route 写 amount=1 并 refresh。调用无分组 count/sum 的生产 Pager，断言失败且不发出任何一行：

```kotlin
ElasticsearchAggregationPager(client, index).execute(plan).test()
    .expectErrorMatches { it is ElasticsearchException }
    .verify(Duration.ofSeconds(15))
```

不把某一个 HTTP 状态固定为跨版本合同；异常应来自原生 ES 搜索。测试前置状态必须保证存在一成功一不可用分片，全部不可用不能证明严格部分结果策略。

- [ ] **Step 4: 验证运行期分片失败不返回部分汇总。** 新建正常的双分片索引，等待两个主分片 STARTED；向 routeA 写 fail=false，routeB 写 fail=true。通过真实编译器编译 `aggregation { count("count"); sum("probe_value", "total") }`，测试在计划的 runtimeMappings 中注入唯一专用字段：

```kotlin
val runtime = RuntimeField.of {
    it.type(RuntimeFieldType.Double).script { script ->
        script.source("if (doc['fail'].value) { throw new IllegalArgumentException('summary-probe'); } emit(1.0);")
    }
}
val failedPlan = plan.copy(runtimeMappings = plan.runtimeMappings + ("probe_value" to runtime))
ElasticsearchAggregationPager(client, index).execute(failedPlan).test()
    .expectErrorMatches { it is ElasticsearchException }
    .verify(Duration.ofSeconds(15))
```

确认抛出的是注入的 script/分片执行异常，避免认证或无效请求也通过；在 report 留下根错误类型/原因。无需复制旧 PIT 查询作为对照，研究证据已经在 `build/query-summary/native-single-index-probe.json`。

- [ ] **Step 5: 聚焦真实验证后执行现有查询集成范围。**

```bash
./gradlew :wow-elasticsearch:integrationTest --tests '*ElasticsearchSummaryExecutionIntegrationTest' --console=plain
./gradlew :wow-elasticsearch:integrationTest --tests 'me.ahoo.wow.elasticsearch.query.*' :wow-elasticsearch:detekt --console=plain
```

第二条覆盖 Snapshot/EventStream 已有根级、嵌套、全部指标和空结果合同。保存日志/XML 计数到 `build/query-summary/`。无源码变化时不重跑 Task 1 已通过单测。若 Detekt 改写源码，检查影响并运行覆盖该改写的最窄检查。

- [ ] **Step 6: 修改双语文档与执行记录。** 将原“没有 group 时始终返回一行”/“always returns”限定为成功查询；紧邻它增加以下文案：

```text
Elasticsearch 的无分组汇总使用一次搜索，并拒绝部分结果。分片不可用、分片执行失败或搜索超时时，查询报错，不返回部分汇总，也不转成空输入行。该策略仅适用于 Elasticsearch 的无分组汇总；MongoDB 和分组查询的失败策略仍由各自后端实现决定。

Elasticsearch executes an ungrouped summary in one search and rejects partial results. Unavailable shards, shard execution failures, or search timeouts fail the query instead of returning a partial summary or an empty-input row. This policy applies only to ungrouped Elasticsearch summaries; MongoDB and grouped queries retain their own backend failure behavior.
```

设计末尾补充实际提交、测试数量/失败/跳过、命令、ES 版本、调用数口径和已验证边界；本计划逐步标记已完成。链接检查与 `git diff --check` 即为这两处纯 Markdown 段落改动的窄验证，不需要重复构建整个 VitePress 站点。

- [ ] **Step 7: 自审并提交。** 提交上述五个文件，message `test(elasticsearch): verify strict summary search contracts`。报告原生错误原因、资源清理、测试数量和日志路径。独立任务审查及阶段整体审查均通过后归档评审证据，保留当前 worktree。
