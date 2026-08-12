# Query Backend、MongoDB 与 Elasticsearch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用同一 Portable Query TCK 实现 MongoDB 与 Elasticsearch `QueryBackend`，完成 mapping/index readiness、storage route resolver 和 Spring 注册；保留 FullText/Native 等显式后端能力，不允许静默降级。

**Architecture:** `QueryGateway` 在 Policy 后用 immutable `QueryBackendResolutionContext` 解析 route-bound backend；该 backend 私有冻结具体 collection/index、logical→physical binding 和 target-specific readiness，`QueryPlanV1` 继续保持后端无关且不携带物理 mapping。`QueryBackend` 只编译/执行 Plan 02 生成的 `QueryPlanV1`。Portable vectors 对两个后端使用同一数据集和期望；物理 compiler 各自把 logical field/expression 编译为 BSON 或 Elasticsearch DSL。Mongo page 以单个 `$facet` 命令获得 items + exact total；Elasticsearch page 以一次 `track_total_hits` search 获得 exact total，无限 list 使用 PIT + `search_after` 并用 Reactor resource scope 保证关闭。

**Tech Stack:** MongoDB Reactive Streams Driver、BSON、Spring Data Elasticsearch Reactive Client、Elasticsearch Java API Client、Reactor、Testcontainers、JUnit Jupiter、FluentAssert。

## Global Constraints

- 必须先完成 [Plan 02](2026-08-12-query-service-architecture-upgrade-02-gateway-policy-runtime.md) 全部 gate。
- 本计划的 Mongo `$facet` 是分页一致性实现细节，不是公开聚合分析 API；不新增 `AnalyticsQueryGateway`、group-by、metric 或跨聚合 join。
- 两个后端的 portable 语义必须由共享 TCK 证明；后端不能修改/忽略 canonical expression。
- FullText 统一使用 `full-text`，不得降级为 CONTAINS；Mongo/ES Native 分别使用 `x-wow:mongo-native`/`x-wow:elasticsearch-native`，不接受任意原文，只允许注册的 template + typed parameters + declared fields。
- mapping/index 不满足时返回 `BACKEND_NOT_READY`；不自动创建迁移、不覆盖现有 index、不静默换物理字段。
- `limit=0` 是无限流但仍受 deadline/budget/backpressure；禁止 ES 10k 静默截断或一次性 materialize 全量结果。
- 真实客户端资源在 complete/error/cancel/deadline 都必须释放；核心路径禁止 `block()`、内部 `subscribe()`。
- 现有公开 Mongo/ES `*QueryServiceFactory` 本阶段暂不删除或改签名；兼容适配在 Plan 04。
- 当前 Gradle task graph 已用 `./gradlew :wow-tck:compileKotlin :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --dry-run --console=plain` 验证无 project/task cycle；后续若修改 dependency scope，必须先重复该 dry-run。
- 容器集成测试不得以 Docker 不可用为“通过”；本机/CI 无容器环境时明确报告未验证，并在可用环境补跑后才能完成 Plan 03。
- `me/ahoo/wow/query/`、`me/ahoo/wow/mongo/query/`、`me/ahoo/wow/elasticsearch/query/` 下每个新增 JVM-public class 都必须在 authoritative ABI dump 中作为 stable surface，或以 exact `class-overrides.tsv` row 标明真实 Kotlin internal/private 理由；禁止 broad package/Kt exclude。每次 dump 后运行 Java/Kotlin external source fixture 和 8-module `queryApiCheck`。

## File responsibility map

| Unit | Single responsibility |
| --- | --- |
| `wow-query/.../backend/QueryBackendResolutionContext.kt` | framework-owned immutable backend resolution input |
| `wow-query/.../backend/QueryBackendFactory.kt` | bind one context to one route-specific backend instance without I/O |
| `test/wow-tck/.../query/backend/*` | single portable semantic/lifecycle oracle shared by all backends |
| `wow-mongo/.../query/backend/*` | BSON compilation, Mongo execution/decoding and bound readiness only |
| `wow-elasticsearch/.../query/backend/*` | ES request compilation, PIT execution/decoding and bound readiness only |
| `wow-*/.../query/schema/*` | logical-to-physical binding and mapping/index compatibility checks |
| `wow-elasticsearch/.../eventsourcing/ElasticsearchQueryPresenceEncoder.kt` | versioned hidden presence/null metadata at the ES write boundary |
| `wow-spring-boot-starter/.../query/StorageRoutingQueryBackendResolver.kt` | reuse the one storage route snapshot and bind the selected factory |

---

## Task 0: 冻结 route-bound Backend 解析合同

**Interfaces consumed:** Plan 02 `QueryBackendResolver`、`QuerySchema`、canonical expression、Gateway lifecycle。

**Interfaces produced:** immutable `QueryBackendResolutionContext`；stable `QueryBackendFactory`；兼容的 resolver context overload；route-bound backend contract。

**Files:**

- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/backend/QueryBackendResolutionContext.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/backend/QueryBackendFactory.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/backend/QueryBackendResolver.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/DefaultQueryGateway.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/QueryInvocation.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/DefaultQueryGatewayTest.kt`
- Modify: `scripts/query-api-source-check.sh`
- Modify: `config/query-api/wow-query-8.x.baseline`

- [ ] **Step 1: 写 context routing RED**

测试 Gateway 在 Policy-output validation 后、Planner 前只调用 `resolve(context)` 一次；context 包含 target、framework-owned immutable Schema snapshot 和最终 secured expression。Policy 拒绝/失败时 resolver 仍为 0。恶意 resolver 修改原 Schema/backing collection 不影响 context；context 手写 structural equals/hash/component/copy，公开 `toString()` 不展开 expression/value。

```kotlin
val contexts = CopyOnWriteArrayList<QueryBackendResolutionContext>()
val resolver = object : QueryBackendResolver {
    override fun resolve(target: QueryTarget): Mono<ResolvedQueryBackend> =
        Mono.error(AssertionError("Gateway used the target-only compatibility path."))

    override fun resolve(context: QueryBackendResolutionContext): Mono<ResolvedQueryBackend> {
        contexts += context
        return ResolvedQueryBackend.resolve(recordingFactory.bind(context), ROUTE_ID)
    }
}
```

成功请求后断言 `contexts.single().securedExpression === combinedPolicyResult.securedExpression`、Schema 是 `QuerySchema` snapshot 且 target 相等；Policy deny 后断言 `contexts.isEmpty()`。

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.DefaultQueryGatewayTest"`

Expected: compile/test failure，因为 context overload 尚不存在且 Gateway 仍只传 target。

- [ ] **Step 2: 添加兼容 resolver overload**

保留现有 SAM abstract `resolve(target)`；新增有默认实现的 `resolve(context)` 并委托 `resolve(context.target)`，从而保持已发布 Java/Kotlin lambda 与第三方 resolver source/binary 兼容。Gateway 只调用 context overload。新增 Java/Kotlin source fixture，锁定旧 target-only 实现、新 context-aware override 与 framework-owned immutable context；另用修改前发布 JAR 预编译一个 target-only resolver，在修改后 classpath 真实调用 context overload，证明没有 `AbstractMethodError`/`NoSuchMethodError`。authoritative ABI dump 只允许 additive surface。

```kotlin
fun interface QueryBackendFactory {
    fun bind(context: QueryBackendResolutionContext): QueryBackend
}

class QueryBackendResolutionContext(
    val target: QueryTarget,
    val schema: QuerySchema,
    val securedExpression: QueryExpression
) {
    operator fun component1(): QueryTarget = target
    operator fun component2(): QuerySchema = schema
    operator fun component3(): QueryExpression = securedExpression
    fun copy(
        target: QueryTarget = this.target,
        schema: QuerySchema = this.schema,
        securedExpression: QueryExpression = this.securedExpression
    ): QueryBackendResolutionContext = QueryBackendResolutionContext(target, schema, securedExpression)
    override fun equals(other: Any?): Boolean = other is QueryBackendResolutionContext &&
        target == other.target && schema == other.schema && securedExpression == other.securedExpression
    override fun hashCode(): Int = listOf(target, schema, securedExpression).hashCode()
    override fun toString(): String =
        "QueryBackendResolutionContext(target=<redacted>, schemaFieldCount=${schema.fields.size}, " +
            "securedExpression=<redacted>)"
}

fun interface QueryBackendResolver {
    fun resolve(target: QueryTarget): Mono<ResolvedQueryBackend>

    fun resolve(context: QueryBackendResolutionContext): Mono<ResolvedQueryBackend> = resolve(context.target)
}
```

`QueryBackendFactory.bind` 必须同步且无 I/O；它防御性持有 context 后创建 backend，mapping/index/template 的异步检查留给 bound backend 的 `readiness()`。

- [ ] **Step 3: 固定 route-bound backend contract**

storage resolver 的 context overload 必须返回每 route/target 绑定的 backend 实例；实例冻结 collection/index/alias、logical→physical binding、schema version/mapping readiness 和当前 secured expression 的 capability requirements。`QueryPlanV1` 不增加物理字段，四个 Backend operation 不增加 context 参数，无参 `readiness()` 只检查已绑定状态。target-only compatibility path 不得成为统一 Gateway 的降级旁路。

Task 0 的 implementation 只创建 context/Factory SPI、把 `QueryInvocation.schema` 的 internal static type 收紧为 `QuerySchema`，并改 Gateway 调用点：

```kotlin
backendResolver.resolve(
    QueryBackendResolutionContext(
        target = evaluated.invocation.request.target,
        schema = evaluated.invocation.schema,
        securedExpression = evaluated.policyResult.securedExpression
    )
)
```

不得在本 Task 提前加入 Mongo/ES 分支、mapping inspector 或 runtime `is ContextAwareResolver` 检测。

- [ ] **Step 4: gate 与提交**

Run: `./gradlew :wow-query:check queryApiDump queryApiCheck --rerun-tasks --console=plain`

Expected: focused、source fixture、8-module ABI 全绿；baseline 只有批准的 additive resolver/context surface。

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/backend \
  wow-query/src/main/kotlin/me/ahoo/wow/query/DefaultQueryGateway.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/DefaultQueryGatewayTest.kt \
  scripts/query-api-source-check.sh config/query-api/wow-query-8.x.baseline
git commit -m "feat: bind query backends to resolution context"
```

## Task 1: 建立跨后端 Portable Query TCK

**Interfaces consumed:** `QueryGatewayFactory`、Backend SPI、Plan 01 portable expression/schema、Plan 02 TestKit。

**Interfaces produced:** 单一 portable dataset/vector、Snapshot/EventStream backend contract spec、lifecycle spec。

**Files:**

- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/PortableQueryDataset.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/PortableQueryVector.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/QueryBackendTestKit.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/SnapshotQueryBackendSpec.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/EventStreamQueryBackendSpec.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/QueryBackendLifecycleSpec.kt`
- Create: `test/wow-tck/src/test/kotlin/me/ahoo/wow/tck/query/backend/PortableQueryVectorTest.kt`

- [ ] **Step 1: 写 vector completeness 失败测试**

以 `PortableOperator.entries` 和 logical operators/system field cases 为 key，断言每项至少有 positive、negative、type/arity boundary vector；另覆盖 null 与 missing 的差异、empty stored collection、nested object collection、Unicode、enum/instant、stable sort tie、projection、page/count/list/single。operand collection 仍服从 validator 的非空约束。vector 必须直接锁定第 5.2 节语义：missing 不命中 `NE`/`NOT_IN`、`NULL`/`NOT_NULL` 要求字段存在、`EXISTS` 区分 missing 与 explicit null、`BETWEEN` 双端包含、字符串元字符按 literal、`ELEM_MATCH` 同 array element。

```kotlin
@Test
fun `every portable operator has positive negative and boundary vectors`() {
    val vectorsByOperator = PortableQueryDataset.vectors
        .filter { it.key is PortableContractKey.Operator }
        .groupBy { (it.key as PortableContractKey.Operator).value }
    vectorsByOperator.keys.assert().containsExactlyInAnyOrder(PortableOperator.entries)
    PortableOperator.entries.forEach { operator ->
        vectorsByOperator.getValue(operator).map(PortableQueryVector::kind).toSet().assert()
            .contains(PortableVectorKind.POSITIVE, PortableVectorKind.NEGATIVE, PortableVectorKind.BOUNDARY)
    }
    PortableQueryDataset.vectors.map(PortableQueryVector::key).assert()
        .contains(
            PortableContractKey.Feature(QueryPortableFeature.ELEMENT_MATCH),
            PortableContractKey.Logical(LogicalOperator.AND),
            PortableContractKey.Logical(LogicalOperator.OR),
            PortableContractKey.Logical(LogicalOperator.NOR)
        )
}
```

Run: `./gradlew :wow-tck:test --tests "me.ahoo.wow.tck.query.backend.*"`

Expected: compile failure，因为 vector 尚不存在。

- [ ] **Step 2: 定义后端无关 dataset**

固定 8～12 个 immutable logical documents，同时生成 Snapshot 与 EventStream document wrapper。至少包含：

- field absent 与 field explicit null；
- scalar/string/boolean/decimal/instant/enum；
- scalar array 和 object array；
- 相同主排序值但不同 system id；
- active/deleted Snapshot；
- tenant/owner/space 分布。

期望结果只用 logical id 集合、稳定顺序、exact total 表示，不能引用 BSON `_id` 或 ES `_source` 细节。

```kotlin
data class PortableQueryDocument(
    val logicalId: String,
    val fields: Map<String, QueryValue>
)

data class PortableQueryVector(
    val id: String,
    val key: PortableContractKey,
    val kind: PortableVectorKind,
    val expression: QueryExpression,
    val expectedLogicalIds: List<String>,
    val expectedError: QueryErrorCode? = null
)

enum class PortableVectorKind { POSITIVE, NEGATIVE, BOUNDARY }

sealed interface PortableContractKey {
    data class Operator(val value: PortableOperator) : PortableContractKey
    data class Feature(val value: QueryPortableFeature) : PortableContractKey
    data class Logical(val value: LogicalOperator) : PortableContractKey
    data class SystemField(val value: LogicalField) : PortableContractKey
}

object PortableQueryDataset {
    val documents: List<PortableQueryDocument>
    val vectors: List<PortableQueryVector>
}
```

构造器必须防御复制；`id` 是低基数稳定 vector key，错误期望只保存 `QueryErrorCode`，不得把 backend request/driver object 放入 TCK 模型。

- [ ] **Step 3: 实现 Gateway-driven Backend TestKit**

TestKit 用固定 Admission/System policy/schema 和单 backend resolver 创建真实 `QueryGateway`；TCK 不直接构造 `QueryPlanV1`。抽象 hook 只负责 backend、prepare dataset、clear dataset、capability/readiness fixture。

```kotlin
abstract class SnapshotQueryBackendSpec {
    protected abstract fun backendFactory(): QueryBackendFactory
    protected abstract fun prepare(dataset: PortableQueryDataset): Mono<Void>
    protected abstract fun clear(): Mono<Void>
}
```

TestKit resolver 在 `resolve(context)` 中调用 `backendFactory().bind(context)` 后委托 `ResolvedQueryBackend.resolve`；target-only 方法在测试中若被调用立即失败，以证明 Gateway 没有走兼容旁路。

- [ ] **Step 4: 固定 operation 与生命周期契约**

四 operation 同时测试 typed/dynamic shape；page exact total；list `limit=0` 逐批请求；single 无结果 empty；cancel/deadline 后客户端 publisher 被取消；不支持 capability 精确返回 `UNSUPPORTED_CAPABILITY`。

每个 vector 由 `QueryGateway` 执行，使用 `StepVerifier` 断言结果/错误和请求量；禁止在 TCK 或 concrete spec 中 `block()`。backend compiler-only 测试可以检查 request object，但不能替代真实 Gateway + container spec。

- [ ] **Step 5: 运行 TCK 自测并提交**

Run: `./gradlew :wow-tck:check`

Expected: vector/TestKit 自测通过；尚无具体后端子类时不要求容器。

```bash
git add test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend \
  test/wow-tck/src/test/kotlin/me/ahoo/wow/tck/query/backend
git commit -m "test: define portable query backend tck"
```

## Task 2: 实现 MongoDB QueryBackend

**Interfaces consumed:** `QueryPlanV1`、logical schema binding、Mongo reactive collection。

**Interfaces produced:** Mongo descriptor/compiler/executor/readiness；Mongo Native/FullText capability。

**Files:**

- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryBackend.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryBackendFactory.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryBackendDescriptor.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryPlanCompiler.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryFieldBinding.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryResultDecoder.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoNativeQueryTemplateRegistry.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryReadiness.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryPlanCompilerTest.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryBackendTest.kt`
- Create: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/backend/MongoSnapshotQueryBackendSpec.kt`
- Create: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/backend/MongoEventStreamQueryBackendSpec.kt`
- Modify: `scripts/query-api-source-check.sh`
- Modify: `config/query-api/class-overrides.tsv`
- Modify: `config/query-api/wow-mongo-8.x.baseline`

- [ ] **Step 1: 写 BSON compiler 失败测试**

逐项消费 TCK portable vectors，另断言 logical→physical Snapshot/EventStream system field；null/missing、`NE`、`NOT_IN`、`EXISTS`、`ELEM_MATCH`、regex escaping；所有集合仅 materialize 一次；unknown field/capability/template 在 collection 调用前失败。

```kotlin
@Test
fun `not-in requires field existence and never matches missing`() {
    val filter = compiler.filter(predicate("state.tag", PortableOperator.NOT_IN, "blocked"))
    filter.toBsonDocument().assert().isEqualTo(
        Document.parse(
            """{"${'$'}and":[{"state.tag":{"${'$'}exists":true}},""" +
                """{"state.tag":{"${'$'}nin":["blocked"]}}]}"""
        )
    )
}
```

不要用 JSON 字符串作为 production compiler 输入；这里只把生成后的 BSON canonical document 作为测试 oracle。

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.backend.*"`

Expected: compile failure，因为 Mongo backend 尚不存在。

- [ ] **Step 2: 实现 factory、descriptor 与 immutable binding skeleton**

Descriptor 只声明实际可执行的 document kinds、Plan V1、portable operators、统一 `full-text`、`x-wow:mongo-native` capabilities 和预算上限；Native expression 同时要求 `backendId=mongo`。Compiler 是纯函数：输入 plan + route-bound backend 私有持有的 immutable Mongo field binding，输出 operation-specific BSON command；不得读取 authority、旧 `Condition` 或 Spring context。

```kotlin
class MongoQueryBackendFactory(
    private val database: MongoDatabase,
    private val nativeTemplates: MongoNativeQueryTemplateRegistry,
    private val maxBudget: QueryBudgetLimit
) : QueryBackendFactory {
    override fun bind(context: QueryBackendResolutionContext): QueryBackend
}

fun interface MongoNativeQueryTemplate {
    fun build(parameters: Map<String, QueryValue>): Bson
}

class MongoNativeQueryTemplateRegistry(
    templates: Map<String, MongoNativeQueryTemplate>
) {
    fun template(templateId: String): MongoNativeQueryTemplate?
}

internal class MongoQueryPlanCompiler(
    private val binding: MongoQueryFieldBinding,
    private val nativeTemplates: MongoNativeQueryTemplateRegistry
) {
    fun filter(expression: QueryExpression): Bson
    fun sort(plan: QueryPlanV1): Bson?
    fun projection(plan: QueryPlanV1): Bson?
}
```

Factory 的 `bind` 只计算 collection name、Schema binding 和 capability requirements，不调用 Mongo client。先让 factory/descriptor/binding snapshot 测试 GREEN；compiler 方法可以继续抛出测试可识别的 unsupported marker，不提前实现表达式。

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.backend.MongoQueryBackendTest"`

Expected: factory bind 不触发 database publisher，descriptor/binding 防御复制测试通过。

- [ ] **Step 3: 实现 equality、presence、null 与 membership compiler**

compiler 的 sealed/logical/operator `when` 必须穷尽；先实现 `EQ`、`NE`、`IN`、`NOT_IN`、`ALL_IN`、`NULL`、`NOT_NULL`、`EXISTS`、`TRUE`、`FALSE`。`NE`/`NOT_IN` 生成 `exists(field,true) AND ...`，`NULL` 生成 `exists AND eq(null)`，`NOT_NULL` 生成 `exists AND ne(null)`。每组先运行对应 method-level test，再运行 compiler test class。

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.backend.MongoQueryPlanCompilerTest"`

Expected: null/missing/membership vectors GREEN；range/string/element/capability vectors仍以明确 unsupported marker失败。

- [ ] **Step 4: 实现 range、literal string、logical 与 ELEM_MATCH compiler**

实现 inclusive `BETWEEN`、四种 range、AND/OR/NOR、MatchAll/MatchNone、三种 string operator 与 `ELEM_MATCH`。字符串 pattern 对每个 regex metacharacter 做 literal escaping；CASE_INSENSITIVE 只在 descriptor/binding 已声明时加 `i`，否则在任何 collection 调用前拒绝。ELEM_MATCH 子字段按当前 element relative path 编译。

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.backend.MongoQueryPlanCompilerTest"`

Expected: 全部 portable compiler vectors GREEN；capability vectors仍未实现。

- [ ] **Step 5: 实现 FullText 与 Native template compiler**

Native registry key 是稳定 `templateId`，value 是服务器注册的 typed BSON builder；调用方参数逐项由 Schema/Policy 已验证，builder 仍使用参数绑定而不是拼接 JSON/BSON 字符串。

Registry 构造时校验非空、安全 template id、拒绝重复并防御复制；公开 `toString()` 只输出 template count，不输出 id/parameter。Template 是服务端受信扩展面，request 只能选择已注册 id，不能上传 builder/Bson。

FullText 只生成 text query；Native 同时校验 capability id、`backendId=mongo`、template id、declared fields 与 registry builder。unknown/mismatch 在 collection 调用前返回稳定 `UNSUPPORTED_CAPABILITY`。

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.backend.MongoQueryPlanCompilerTest"`

Expected: compiler class全部 GREEN；template builder 收到 typed immutable parameters，生产代码无 JSON/BSON string 拼接。

- [ ] **Step 6: 实现 single/list/count 与 decoder**

single 使用 filter + stable sort + projection + limit(1)；有限 list 使用 limit/batch size；`limit=0` 不设置 driver limit，并按 effective budget 设置 batch size。typed/dynamic decoder 只返回已授权 result shape；decode 错误保留 result stage，不能跳过 document。

Recording publisher 分别锁定 `request(n)`、cancel 和 command count；production adapter 只把 Mongo Reactive Streams `Publisher` 转为 Reactor，不调用 `collectList()` 或内部 `subscribe()`。

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.backend.MongoQueryBackendTest"`

- [ ] **Step 7: 用单次 `$facet` 实现 exact page**

生成一个 aggregate pipeline：

```text
$match
$sort
$facet {
  items: [$skip, $limit, $project],
  total: [$count]
}
```

当 page items 为空时仍解析 total；一次 subscription 只能向 collection 发出一个 aggregate command。测试用 recording collection 断言不是 `countDocuments + find` 两次调用。

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.backend.MongoQueryBackendTest"`

- [ ] **Step 8: 实现 readiness 与资源终止语义**

basic readiness 验证 collection/codec/system field binding；请求 FullText 时额外验证兼容 text index；Native 验证 template 已注册。driver publisher 在 cancel/deadline/partial decode failure 时收到 cancel；不需要显式关闭 cursor 的驱动路径仍必须通过 publisher lifecycle test 证明终止。

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.backend.MongoQueryBackendTest"`

- [ ] **Step 9: 接入共享 TCK integration spec**

两个 concrete spec 使用现有 Mongo Testcontainers fixture，准备同一 logical dataset，覆盖 Snapshot/EventStream。不得复制期望矩阵到 Mongo 模块。

Run: `./gradlew :wow-mongo:check :wow-mongo:integrationTest queryApiDump queryApiCheck --rerun-tasks --stacktrace`

Expected: unit + 两套 TCK 通过，page command count 为 1，`limit=0` 无截断；Java/Kotlin consumer 可构造 factory/注册 Native template，但不能命名 internal bound backend/compiler。

- [ ] **Step 10: 提交**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/backend \
  wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/backend \
  scripts/query-api-source-check.sh config/query-api/class-overrides.tsv \
  config/query-api/wow-mongo-8.x.baseline
git commit -m "feat: implement mongodb query backend"
```

## Task 3: 实现 Elasticsearch QueryBackend 与 PIT 生命周期

**Interfaces consumed:** `QueryPlanV1`、logical mapping binding、Reactive Elasticsearch client。

**Interfaces produced:** ES descriptor/compiler/executor/PIT/readiness；ES Native/FullText capability。

**Files:**

- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryBackend.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryBackendFactory.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryBackendDescriptor.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryPlanCompiler.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryFieldBinding.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryResultDecoder.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryPresenceBinding.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/eventsourcing/ElasticsearchQueryPresenceEncoder.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchNativeQueryTemplateRegistry.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/PitSearchAfterExecutor.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryReadiness.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryPlanCompilerTest.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryBackendTest.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/backend/PitSearchAfterExecutorTest.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/eventsourcing/ElasticsearchQueryPresenceEncoderTest.kt`
- Create: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchSnapshotQueryBackendSpec.kt`
- Create: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchEventStreamQueryBackendSpec.kt`
- Modify: `scripts/query-api-source-check.sh`
- Modify: `config/query-api/class-overrides.tsv`
- Modify: `config/query-api/wow-elasticsearch-8.x.baseline`

- [ ] **Step 1: 写 Query DSL 与 mapping 失败测试**

逐项消费 portable vectors；重点覆盖 `keyword` vs analyzed text、root/deep/nested-element presence metadata 上的 null/missing/empty collection、nested `ELEM_MATCH`、escaped wildcard/prefix、stable sort keyword binding、source filtering。字段需要 nested 但 mapping 不是 nested，或 index 缺 `wow_query_presence_version=1` 时返回 `BACKEND_NOT_READY`，不能编译成 object query、`null_value` sentinel 或 `_source` script 冒充语义。

```kotlin
@Test
fun `null uses the nearest object presence metadata`() {
    compiler.query(predicate("state.address.city", PortableOperator.NULL)).assert()
        .isEqualTo(termQuery("state.address.__wow_query.null", "city"))
}

@Test
fun `nested element null uses the metadata inside the same nested element`() {
    compiler.query(elementMatch("state.items", predicate("note", PortableOperator.NULL))).assert()
        .isEqualTo(nestedTermQuery("state.items", "state.items.__wow_query.null", "note"))
}
```

Run: `./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.backend.*"`

Expected: compile failure，因为 ES backend 尚不存在。

- [ ] **Step 2: 实现 presence encoder、factory、descriptor 与 binding skeleton**

FullText 以统一 `full-text` capability 编译为配置的 multi-match/query-string 变体并声明 analyzer 语义；Native 只接受 `x-wow:elasticsearch-native` 且 `backendId=elasticsearch`，调用注册 template builder，禁止原始 JSON。Compiler 输入 plan + route-bound backend 私有持有的 immutable ES mapping binding，输出 Java client request object，不看旧 `Condition`。

```kotlin
class ElasticsearchQueryBackendFactory(
    private val client: ReactiveElasticsearchClient,
    private val nativeTemplates: ElasticsearchNativeQueryTemplateRegistry,
    private val maxBudget: QueryBudgetLimit
) : QueryBackendFactory {
    override fun bind(context: QueryBackendResolutionContext): QueryBackend
}

fun interface ElasticsearchNativeQueryTemplate {
    fun build(parameters: Map<String, QueryValue>): Query
}

class ElasticsearchNativeQueryTemplateRegistry(
    templates: Map<String, ElasticsearchNativeQueryTemplate>
) {
    fun template(templateId: String): ElasticsearchNativeQueryTemplate?
}

internal class ElasticsearchQueryPlanCompiler(
    private val binding: ElasticsearchQueryFieldBinding,
    private val nativeTemplates: ElasticsearchNativeQueryTemplateRegistry
) {
    fun query(expression: QueryExpression): Query
    fun sort(plan: QueryPlanV1): List<SortOptions>
    fun sourceFilter(plan: QueryPlanV1): SourceConfig?
}
```

Factory 只冻结 index/alias、binding 和 capability requirements，不调用 client。先实现 factory/descriptor/binding snapshot 与 encoder；compiler 仍可用明确 unsupported marker。

同一步实现 `ElasticsearchQueryPresenceEncoder` 纯函数和单测，concrete integration spec 用它准备 test documents；Task 4 再把同一 encoder 接入 production direct/batch writers，禁止在 integration fixture 复制 metadata 算法。

Run: `./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoderTest" --tests "me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryBackendTest"`

Expected: encoder/factory snapshot GREEN，且 factory 不调用 reactive client。

- [ ] **Step 3: 实现 portable Query DSL compiler**

按 TCK 分组实现 equality/range/membership、logical、presence/null、literal string 和 nested ELEM_MATCH。普通 `NE`/`NOT_IN`/presence/null predicate 必须使用版本化 presence metadata；字符串三操作在 exact keyword/wildcard binding 上 escape 所有 wildcard 字符，不能使用 `matchPhrase`。ELEM_MATCH 必须生成 nested query 并在当前 nested element 内绑定 metadata。

Run: `./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryPlanCompilerTest"`

Expected: portable vectors GREEN；FullText/Native vectors仍以明确 unsupported marker失败。

- [ ] **Step 4: 实现受控 FullText 与 Native compiler**

FullText 只走 SEARCH binding，并保留声明的 analyzer/query semantics。Native 逐项校验 `x-wow:elasticsearch-native`、`backendId=elasticsearch`、template、typed parameters 和 declared fields；registry builder 创建 Java client `Query`，不接收 raw JSON/String。

Registry 与 Mongo 采用相同 id/快照/脱敏规则；request 不能提供 Java client `Query` 或 builder。

Run: `./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryPlanCompilerTest"`

Expected: compiler test class全部 GREEN，unknown/mismatch 在 client 调用前失败。

- [ ] **Step 5: 实现 single/page/count**

single 是 size=1 search；page 使用一次 search，`from/size` 在 readiness/budget 允许范围内，`track_total_hits=true` 并要求 exact relation；若 ES 返回 lower-bound/unknown total，返回 `BACKEND_FAILURE`，不能冒充精确值。count 使用 count API。typed/dynamic decode 均服从 authorized source filter。

Run: `./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryBackendTest"`

- [ ] **Step 6: TDD 实现有限 list 与无限 PIT + search_after**

有限 list 小于单次 size 上限时可单 search；超过单批或 `limit=0` 时必须：open PIT → search stable page → emit with backpressure → use last sort values as `search_after` → repeat → close PIT。

```kotlin
Flux.usingWhen(
    openPointInTime(),
    { pit -> searchAfterPages(pit, plan) },
    ::closePointInTime,
    { pit, _ -> closePointInTime(pit) },
    ::closePointInTime
)
```

测试 complete/error/cancel/deadline/decoder failure 五条路径都恰好 close 一次；不预取无限 pages，不用 `collectList()`，不把 0 改成 10,000。

Run: `./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.backend.PitSearchAfterExecutorTest"`

- [ ] **Step 7: 实现 bound readiness 并接入共享 TCK integration spec**

两个 concrete spec 使用现有 Elasticsearch Testcontainers fixture；创建符合声明 Schema 的测试 index/mapping。测试完成后删除测试 index/PIT，不修改项目默认 template。

readiness 对当前 context 需要的 plan version、portable feature/string mode、FullText analyzer、Native template、nested mapping 和 presence version逐项检查；失败不发 search/open-PIT command。

Run: `./gradlew :wow-elasticsearch:check :wow-elasticsearch:integrationTest queryApiDump queryApiCheck --rerun-tasks --stacktrace`

Expected: unit + 两套 TCK 通过；PIT resource assertions 全通过；无 10k 截断；Java/Kotlin consumer 可构造 factory/注册 Native template，但不能命名 internal bound backend/compiler/PIT executor。

- [ ] **Step 8: 提交**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/eventsourcing/ElasticsearchQueryPresenceEncoder.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/backend \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/eventsourcing/ElasticsearchQueryPresenceEncoderTest.kt \
  wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/backend \
  scripts/query-api-source-check.sh config/query-api/class-overrides.tsv \
  config/query-api/wow-elasticsearch-8.x.baseline
git commit -m "feat: implement elasticsearch query backend"
```

## Task 4: 绑定 Query Schema 与真实 mapping/index readiness

**Interfaces consumed:** `QuerySchemaCustomizer`、Mongo index metadata、Elasticsearch mapping API、现有 initializer/template。

**Interfaces produced:** route-bound backend 私有持有的 logical→physical binding；调用期 target-specific readiness report；显式迁移错误。

**Files:**

- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaCustomizer.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaCustomizer.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchMappingInspector.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchMappingReadiness.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/eventsourcing/ElasticsearchQueryPresenceEncoder.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaCustomizerTest.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchMappingInspectorTest.kt`
- Create: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchMappingReadinessTest.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/IndexTemplateInitializer.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/eventsourcing/ElasticsearchSnapshotWrite.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/eventsourcing/ElasticsearchEventStreamAppender.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/eventsourcing/BatchElasticsearchEventStreamAppender.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryService.kt`
- Modify: `wow-elasticsearch/src/main/resources/templates/wow-snapshot-template.json`
- Modify: `wow-elasticsearch/src/main/resources/templates/wow-event-stream-template.json`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/AggregateSchemaInitializer.kt`
- Modify: `scripts/query-api-source-check.sh`
- Modify: `config/query-api/class-overrides.tsv`
- Modify: `config/query-api/wow-mongo-8.x.baseline`
- Modify: `config/query-api/wow-elasticsearch-8.x.baseline`

- [ ] **Step 1: 写 mapping readiness 矩阵失败测试**

覆盖 missing field、wrong scalar type、text without keyword sort binding、object vs nested、date numeric mismatch、`ignore_above` 风险、system field missing、missing/wrong `wow_query_presence_version`、reserved namespace collision、template only affects new index。错误必须带安全 logical field/code/index alias，不带 mapping 全文。

```kotlin
@Test
fun `an index without presence metadata version is not ready`() {
    inspector.inspect(schema, mappingWithoutPresenceVersion).assert()
        .isEqualTo(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE))
}
```

- [ ] **Step 2: 实现唯一 customizer 的 backend binding**

不要新增第二个 Schema 真相源。Mongo 默认 physical path 等于 Jackson logical path，只覆盖 framework wrapper fields；ES customizer 为 managed index 声明固定 logical→physical/multi-field/nested binding，并把每个 logical field 的存在/null predicate 绑定到最近父 object 的 `__wow_query.present/null` exact keyword。既有/custom index 仍由 inspector 验证，不从 mapping 反向生成 Query Schema。

- [ ] **Step 3: 写入并隐藏版本化 presence metadata**

对 Snapshot 与 EventStream 的 direct/batch document map 使用同一个纯 encoder：每个 object 只遍历一次，复制业务字段并加入 `__wow_query.present` 与 `__wow_query.null`；object array 元素递归拥有自己的 metadata。encoder 拒绝输入业务字段占用 `__wow_query`，不修改调用方 Map/List。新 Backend typed/dynamic result decoder与暂时保留的 legacy `AbstractElasticsearchQueryService` 都递归移除 metadata，projection 不能把它暴露给调用方。模板 mapping `_meta.wow_query_presence_version=1`，dynamic template 将任意深度的 metadata arrays 映射为 keyword。

```kotlin
internal object ElasticsearchQueryPresenceEncoder {
    const val FIELD = "__wow_query"
    const val VERSION = 1
    fun encode(document: Map<String, Any?>): Map<String, Any?>
    fun strip(document: Map<String, Any?>): Map<String, Any?>
}
```

测试锁定 explicit null、missing、empty list/object、deep object、nested object list、输入不变、reserved collision，以及 direct/batch Snapshot/EventStream 使用同一 encoder；legacy dynamic single/list/page 结果也必须零 `__wow_query` 字段。

- [ ] **Step 4: 实现 readiness cache 与失效边界**

readiness key 包含 backend/index-or-collection/schema version；成功可有界缓存，失败不得永久缓存。index alias 指向新 index 或 initializer 完成后显式 invalidate；resolver context overload 创建的 route-bound backend 冻结 binding 与 readiness snapshot，后续 Planner/execute 不重读 mapping。FullText/text index、Native template 和 nested `ELEM_MATCH` readiness 按当前 secured expression 的实际要求验证，不把 generic client ping 冒充 target readiness。

- [ ] **Step 5: 保证 initializer 不自动迁移**

initializer 可为新资源创建含 presence version/mapping 的 template/schema/index，但发现已有不兼容 index 时只报告 `BACKEND_NOT_READY` 和迁移文档 key；禁止用 put mapping 给旧索引补版本并假装旧文档已编码，禁止 delete/recreate、强改 incompatible type 或 alias swap。

- [ ] **Step 6: 运行验证并提交**

Run: `./gradlew :wow-mongo:check :wow-elasticsearch:check :wow-elasticsearch:integrationTest queryApiDump queryApiCheck --rerun-tasks --stacktrace`

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/{query/schema,AggregateSchemaInitializer.kt} \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/{query/schema,eventsourcing,IndexTemplateInitializer.kt} \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryService.kt \
  wow-elasticsearch/src/main/resources/templates/wow-snapshot-template.json \
  wow-elasticsearch/src/main/resources/templates/wow-event-stream-template.json \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/schema \
  wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/schema \
  scripts/query-api-source-check.sh config/query-api/class-overrides.tsv \
  config/query-api/wow-mongo-8.x.baseline config/query-api/wow-elasticsearch-8.x.baseline
git commit -m "feat: validate query backend readiness"
```

## Task 5: 统一 storage routing 与 Spring Backend 注册

**Interfaces consumed:** 当前 `StorageRoutingProperties`、`StorageType`、Mongo/ES Backends、Plan 02 auto-config。

**Interfaces produced:** 唯一 immutable storage route snapshot；context-aware `QueryBackendResolver`；route-bound backend bindings；运行时 unavailable fail-closed，无 NoOp fallback。

**Files:**

- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/routing/StorageRouteBindings.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/routing/StorageRouteResolver.kt`
- Create: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/StorageRoutingQueryBackendResolver.kt`
- Create: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryBackendRouteSnapshot.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryGatewayAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/routing/StorageRouteResolverTest.kt`
- Create: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/query/StorageRoutingQueryBackendResolverTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfigurationTest.kt`
- Modify: `scripts/query-api-source-check.sh`
- Modify: `config/query-api/class-overrides.tsv`
- Modify: `config/query-api/wow-spring-boot-starter-8.x.baseline`

- [ ] **Step 1: 写 route compatibility/fail-fast 失败测试**

相同 aggregate storage property 必须让 EventStore/SnapshotStore 与对应 query backend 选择一致；支持默认 storage、per-aggregate storage、named binding。显式配置的 missing/invalid binding、binding/backend document kind 不匹配在 context startup 失败；合法但未配置查询 backend 的 target 在调用时返回 `BACKEND_NOT_READY`，不返回 NoOp/empty。

```kotlin
@Test
fun `the same aggregate storage route selects the matching query backend factory`() {
    val properties = StorageRoutingProperties(
        aggregates = mapOf(
            "order" to AggregateStorageRouteProperties(
                snapshot = StorageChannelRouteProperties(binding = "mongo-secondary")
            )
        )
    )
    resolver.resolveSnapshotRoutes(properties).snapshotRoutes.getValue(ORDER).assert()
        .isSameAs(mongoSecondarySnapshotStore)
    resolver.resolveQueryBackendRoutes(properties).binding(orderSnapshotContext().target)!!.backendFactory.assert()
        .isSameAs(mongoSecondaryQueryBackendFactory)
}
```

- [ ] **Step 2: 添加 backend binding，不建立第二套路由表**

```kotlin
internal data class QueryBackendBinding(
    val name: String,
    val storage: StorageType?,
    val backendFactory: QueryBackendFactory
)

internal class QueryBackendRouteSnapshot(
    defaultBindings: Map<QueryDocumentKind, QueryBackendBinding>,
    routeOverrides: Map<QueryTarget, QueryBackendBinding>
) {
    val defaultBindings: Map<QueryDocumentKind, QueryBackendBinding>
    val routeOverrides: Map<QueryTarget, QueryBackendBinding>
    fun binding(target: QueryTarget): QueryBackendBinding? =
        routeOverrides[target] ?: defaultBindings[target.documentKind]
}
```

构造器对两个 Map 做 insertion-order-preserving defensive copy，校验 override key 的 document kind 与 binding 支持相符；同一 target 不得出现两个 binding。`StorageRouteResolver.resolveQueryBackendRoutes(properties)` 一次产生该 snapshot，不能在 query invocation 时重新读取 Spring beans/properties。

`StorageRouteResolver` 在解析现有 event/snapshot channel 时同时解析 backend binding，产生一份含 document-kind defaults 与 per-target overrides 的 immutable snapshot；`StorageRoutingQueryBackendResolver.resolve(context)` 只读取该快照并让 factory 以 context 创建 route-bound backend。旧 `resolve(target)` 只保留已发布 SPI compatibility，统一 Gateway 不调用它。route identity 包含安全的 binding name/document kind，不含连接串。

```kotlin
internal class StorageRoutingQueryBackendResolver(
    routeSnapshot: QueryBackendRouteSnapshot
) : QueryBackendResolver {
    private val routes = routeSnapshot

    override fun resolve(target: QueryTarget): Mono<ResolvedQueryBackend> = backendNotReady()

    override fun resolve(context: QueryBackendResolutionContext): Mono<ResolvedQueryBackend> =
        Mono.defer {
            val binding = routes.binding(context.target) ?: return@defer backendNotReady()
            val backend = binding.backendFactory.bind(context)
            ResolvedQueryBackend.resolve(backend, binding.safeRouteIdentity(context.target))
        }

    private fun backendNotReady(): Mono<ResolvedQueryBackend>
    private fun QueryBackendBinding.safeRouteIdentity(target: QueryTarget): QueryBackendRouteIdentity
}
```

这里的 `backendNotReady()` 返回稳定 `QueryException(BACKEND_NOT_READY, BACKEND_RESOLUTION, BACKEND_UNAVAILABLE)`；不得返回 `Mono.empty()`。Factory `bind` 抛出的配置/绑定异常映射到同一安全边界，不把 collection/index/connection string 放入异常 message。

- [ ] **Step 3: 注册 Mongo/ES backend beans**

复用已有 clients、collection/index name converter、serializer 和 enabled properties；每个 storage auto-config 暴露一个明确 backend factory binding，并在 starter 注册 Mongo/ES 的唯一 `QuerySchemaCustomizer`。custom backend 可通过 named `QueryBackendBinding` 参与现有 `binding` property，但不能替换 System policy、建立第二个 Schema SPI 或绕过 resolver。

Spring bean name 不作为 route identity：同一个 bean name 不能同时注册 Store 与 QueryBackendBinding。resolver 收集所有 `QueryBackendBinding` 后按其稳定 `name` 属性建立唯一索引；named storage route `binding=archive-snapshot-store` 要求存在 Store bean `archive-snapshot-store`，并存在任意 bean name、但 `QueryBackendBinding.name == "archive-snapshot-store"` 的 backend binding。重复 logical name 启动失败。

- [ ] **Step 4: 区分非法显式配置与运行时 unavailable**

显式 route/binding 名称非法或引用不存在配置时 startup fail-fast。仅仅启用 QueryGateway 而没有默认 backend，或合法 target 的 backend/index 暂不可用时，保留 Plan 02 unavailable resolver 的运行时 `BACKEND_NOT_READY`；默认不阻止不使用该 backend 的应用启动。不能退回 `NoOp*QueryServiceFactory`、empty result，或把非法显式配置降级为 unavailable。

- [ ] **Step 5: 锁定默认 Jackson Schema 真实链路**

新增不替换 `QuerySchemaResolver` 的 Spring integration test：用真实 aggregate metadata、默认 `JacksonQuerySchemaResolver`、backend customizers、storage routing、route-bound backend 和真实 Gateway 完成至少一个 Snapshot 与一个 EventStream 查询；断言 Schema 只解析一次、物理 binding 来自同一 Schema 快照、Policy 拒绝时 backend resolver 为 0。这是 Plan 02 deferred Jackson E2E gate，不能用 stub resolver 代替。

测试配置只允许替换实际 client/command transport 为 recording backend factory；不得替换 `MetadataSearcher`、`JacksonQuerySchemaResolver`、customizer merge、storage route resolver、Policy chain 或 Gateway。记录 factory 收到的 `QueryBackendResolutionContext.schema`，与 compiler binding 的 logical fields 逐项相等。

- [ ] **Step 6: 运行模块与真实容器验收**

Run:

```bash
./gradlew :wow-spring-boot-starter:check \
  :wow-mongo:check :wow-mongo:integrationTest \
  :wow-elasticsearch:check :wow-elasticsearch:integrationTest \
  :wow-tck:check queryApiDump queryApiCheck --rerun-tasks --stacktrace
```

Expected: 全部通过；route test 证明同一 storage properties 决定 store 与 backend；错误 binding 不再静默。

- [ ] **Step 7: 提交**

```bash
git add wow-spring-boot-starter/src/main wow-spring-boot-starter/src/test \
  scripts/query-api-source-check.sh config/query-api/class-overrides.tsv \
  config/query-api/wow-spring-boot-starter-8.x.baseline
git commit -m "feat: route query backends by storage binding"
```

## Task 6: 验证资源上界与后端能力不降级

**Interfaces consumed:** 两个真实 Backend、TCK dataset、Micrometer test registry。

**Interfaces produced:** 可重复的背压/lifecycle/command-count 验收测试；不做无证据性能宣传。

**Files:**

- Create: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryResourceBoundTest.kt`
- Create: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryResourceBoundTest.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/QueryCapabilityContract.kt`

- [ ] **Step 1: 写可重复资源测试**

准备超过两个 batch 的 dataset，使用 `StepVerifier` 每次 request 小批量元素；断言 Mongo publisher/ES page fetch 不超出配置 prefetch，取消后无后续 command。记录 command/page count 与最大 buffered items，不以 wall-clock 作为唯一断言。

```kotlin
StepVerifier.create(gateway.list(request), 0)
    .thenRequest(3)
    .expectNextCount(3)
    .thenCancel()
    .verify()
recordingTransport.maxBuffered.assert().isLessThanOrEqualTo(configuredPrefetch)
recordingTransport.commandsAfterCancel.assert().isZero()
```

- [ ] **Step 2: 验证 capability 三方门槛**

对 FullText/Native 分别组合 backend support、config enabled、Policy grant；八种组合只有三者全真执行。显式 Policy `DENY` 永远拒绝；全 `ABSTAIN` 拒绝；不支持时不生成后端 command。

```kotlin
data class QueryCapabilityCase(
    val backendSupported: Boolean,
    val configured: Boolean,
    val policyGranted: Boolean,
    val expectedExecutionCount: Int,
    val expectedError: QueryErrorCode?
)

val cases = listOf(false, true).flatMap { backend ->
    listOf(false, true).flatMap { configured ->
        listOf(false, true).map { policy ->
            val allowed = backend && configured && policy
            QueryCapabilityCase(
                backend,
                configured,
                policy,
                expectedExecutionCount = if (allowed) 1 else 0,
                expectedError = when {
                    allowed -> null
                    !policy -> QueryErrorCode.POLICY_DENIED
                    else -> QueryErrorCode.UNSUPPORTED_CAPABILITY
                }
            )
        }
    }
}
```

Policy explicit deny 仍期望 `POLICY_DENIED`，因此在 matrix assertion 中先区分 Policy 决策，再断言 backend/config 不支持为 `UNSUPPORTED_CAPABILITY`。FullText 使用 `full-text`；Native 分别执行 Mongo/ES 的两个 extension id，不能复用一个假 capability。

- [ ] **Step 3: fresh run 阶段验收并提交**

Run:

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest \
  :wow-mongo:check :wow-elasticsearch:check :wow-tck:check queryApiCheck --stacktrace
```

```bash
git add wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/backend \
  wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/backend \
  test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend
git commit -m "test: verify query backend resource bounds"
```

## Plan 03 完成检查

- [ ] MongoDB 与 Elasticsearch concrete specs 使用同一 vector/dataset，未复制 portable expectations。
- [ ] Mongo page 一条 aggregate `$facet`；ES page 一条 exact-total search；ES unlimited list 使用 PIT + `search_after`。
- [ ] PIT complete/error/cancel/deadline/decode-failure 均 close 恰好一次。
- [ ] mapping/index 不兼容只返回 `BACKEND_NOT_READY`，没有自动迁移或删除资源。
- [ ] ES managed index presence metadata version=1；null/missing/empty/deep/nested vectors通过，decoder 不暴露 `__wow_query`；旧索引缺版本时明确 not-ready。
- [ ] FullText/Native 不降级，Native 没有 raw string 拼接入口。
- [ ] storage routing 的 invalid explicit binding startup fail-fast；合法 missing/unready backend 运行时返回 `BACKEND_NOT_READY`；没有新路径返回 NoOp/empty。
- [ ] Gateway 使用 immutable resolution context；同一 invocation 的 backend 是绑定 route/schema/mapping/readiness 的实例；`QueryPlanV1` 无物理字段。
- [ ] 默认 `JacksonQuerySchemaResolver` 经真实 metadata→customizer→storage resolver→Gateway→backend 完成 Snapshot/EventStream E2E，没有 stub resolver。
- [ ] fresh run：`./gradlew :wow-mongo:check :wow-mongo:integrationTest :wow-elasticsearch:check :wow-elasticsearch:integrationTest :wow-tck:check :wow-spring-boot-starter:check queryApiCheck --stacktrace`。
- [ ] 执行 `superpowers:verification-before-completion` 后，再开始 Plan 04。
