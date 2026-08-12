# Query 契约锁定与语义核心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不切换任何运行时入口的前提下，先锁定 Wow 8.x 查询兼容面，并交付后续 Gateway、Policy 与 Backend 共用的不可变公开请求模型、穷尽语义 lowering、Query Schema 和验证器。

**Architecture:** 新稳定值对象放入 `wow-api`；Normalizer、Schema resolver 和 validator 放入 `wow-query` 且保持后端无关。旧 `Condition` 只在兼容边界降低为 canonical expression；canonical model 不携带 BSON、Elasticsearch DSL、Spring、HTTP 或驱动类型。ABI gate 以当前构建产物为 baseline，只允许新增符号和批准清单里的 Filter 删除。

**Tech Stack:** Kotlin 2.4.10、Java 17、Reactor、Jackson、JUnit Jupiter 6、FluentAssert、Gradle Kotlin DSL、JDK `jar`/`javap`。

## Global Constraints

- 先执行并阅读总路线：[2026-08-12-query-service-architecture-upgrade-roadmap.md](2026-08-12-query-service-architecture-upgrade-roadmap.md)。
- 本计划只做 additive contract/semantic core；不接入 Spring，不切换旧 `QueryService`，不删除旧 Filter，不实现真实 Backend。
- 所有公开数据类必须不可变；构造时对集合、Map、数组防御性复制，之后不传播 `Any`。
- 旧 `Operator` 的 lowering 必须穷尽且没有 `else`；逻辑否定使用 `NOR`，不得新增 `NOT`。
- MongoDB 当前可观察语义是 Portable TCK 的参考 oracle，但语义模型不能依赖 MongoDB 类型。
- 失败测试先行；完成每个任务后运行窄测试并提交。

---

## Task 1: 锁定 Wow 8.x 查询 API/ABI 与 source compatibility

**Interfaces consumed:** 当前 `wow-api`、`wow-query`、`wow-webflux`、`wow-spring`、`wow-spring-boot-starter`、`wow-mongo`、`wow-elasticsearch`、`wow-cosec` 的查询相关 public/protected JVM 符号；规格第 10.1 节兼容矩阵。

**Interfaces produced:** `queryApiDump`、`queryApiCheck` Gradle 任务；八份不可手改的当前 baseline；唯一批准删除清单；各模块本地 Kotlin/Java source fixture；ABI 脚本行为测试。

**Files:**

- Create: `scripts/query-api-abi.sh`
- Create: `scripts/query-api-abi-test.sh`
- Create: `config/query-api/modules.tsv`
- Create: `config/query-api/wow-api-8.x.baseline`
- Create: `config/query-api/wow-query-8.x.baseline`
- Create: `config/query-api/wow-webflux-8.x.baseline`
- Create: `config/query-api/wow-spring-8.x.baseline`
- Create: `config/query-api/wow-spring-boot-starter-8.x.baseline`
- Create: `config/query-api/wow-mongo-8.x.baseline`
- Create: `config/query-api/wow-elasticsearch-8.x.baseline`
- Create: `config/query-api/wow-cosec-8.x.baseline`
- Create: `config/query-api/approved-removals.txt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/compat/LegacyQueryApiSourceCompatibilityTest.kt`
- Create: `wow-query/src/test/java/me/ahoo/wow/query/compat/LegacyQueryApiJavaCompatibilityTest.java`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/LegacyWebFluxQueryApiSourceCompatibilityTest.kt`
- Create: `wow-spring/src/test/kotlin/me/ahoo/wow/spring/query/LegacySpringQueryApiSourceCompatibilityTest.kt`
- Create: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/query/LegacyQueryStarterApiSourceCompatibilityTest.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/LegacyMongoQueryApiSourceCompatibilityTest.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/LegacyElasticsearchQueryApiSourceCompatibilityTest.kt`
- Create: `wow-cosec/src/test/kotlin/me/ahoo/wow/cosec/query/LegacyCoSecQueryApiSourceCompatibilityTest.kt`
- Modify: `build.gradle.kts`

- [ ] **Step 1: 先写 ABI 脚本的行为测试并观察 RED**

`scripts/query-api-abi-test.sh` 在 `mktemp -d` 中用 `javac`/`jar` 构造受控 mini API v1/v2，并通过命令行参数把临时 module manifest、artifact dir、baseline dir 和 allowlist 传给被测脚本。测试必须覆盖：新增 public method 允许；删除/descriptor 改变失败；精确 allowlist 删除允许；allowlist symbol 不存在于 baseline 时失败；缺 jar/JDK tool/空 baseline 失败。测试清理只删除自己创建的临时目录。

```bash
bash scripts/query-api-abi-test.sh
```

Expected RED: 非零退出，明确因为 `scripts/query-api-abi.sh` 尚不存在；不能因 fixture 编译错误或命令拼写错误失败。

- [ ] **Step 2: 写归属模块内的 source compatibility fixtures**

`wow-query` fixture 覆盖 `QueryService` 七方法、Snapshot/EventStream service/factory/routing 与 masker；WebFlux fixture 覆盖 `RewriteRequestCondition`、默认实现和 route handler/factory class symbols，但不锁定参数中引用已批准删除 `QueryHandler` 的旧 constructor descriptor；这些 descriptor 只由 ABI baseline 与 Plan 04 的精确 allowlist 管理。MongoDB/Elasticsearch fixtures 覆盖公开 query service/factory constructor；Spring/starter fixtures 覆盖 registrar、query auto-configuration、storage routing binding/property；CoSec fixture 覆盖 `CoSecRewriteRequestCondition`。Java fixture 覆盖核心 Java 可见签名。批准删除的 Filter/Handler/Context 不进入保留 fixture。

每个 fixture 只引用本模块及其正常依赖，禁止为兼容测试新增 `wow-query -> wow-webflux`、storage -> starter 或其他反向依赖。fixture 不声明伪造的 `@Test`：把签名调用放入不执行的 private compile-only 方法，由 `compileTestKotlin`/`compileTestJava` 本身作为 source compatibility gate。

- [ ] **Step 3: 编译 source fixtures，确认当前契约基线通过**

Run:

```bash
./gradlew :wow-query:compileTestKotlin :wow-query:compileTestJava \
  :wow-webflux:compileTestKotlin :wow-spring:compileTestKotlin \
  :wow-spring-boot-starter:compileTestKotlin :wow-mongo:compileTestKotlin \
  :wow-elasticsearch:compileTestKotlin :wow-cosec:compileTestKotlin
```

Expected: `BUILD SUCCESSFUL`。若当前公开签名与规格矩阵不一致，先把差异写入规格并请求确认，不修改 fixture 迁就实现。

- [ ] **Step 4: 实现不依赖第三方插件的 ABI dump/check 脚本**

`scripts/query-api-abi.sh dump|check` 必须：

1. 从 `config/query-api/modules.tsv` 读取八个 module、jar glob 与精确 query package prefix；测试可通过 `--manifest`、`--artifacts-dir`、`--baseline-dir`、`--allowlist` 指向临时 fixture，不读取隐式环境变量；
2. 用 `jar tf` 查找 manifest 声明 package 下的 public class；排除 `*Test*`、`META-INF` 和 Kotlin synthetic helper；
3. 对每个 class 执行 `javap -classpath <jar-and-runtime-classpath> -public -s`，去掉 jar 绝对路径和非确定性空白后排序；
4. `dump` 原子写入每模块 baseline；`check` 计算 `baseline - current - approved-removals`，允许新增符号，但任何未批准的删除/descriptor 改变都失败；
5. `approved-removals.txt` 只列规格第 10.1 节明确批准的 Filter、Handler、Context、Tail/Masking/ABAC filter JVM symbol，不使用包级通配符。

脚本必须在缺 jar、缺 JDK 工具、baseline 为空或 allowlist 命中不存在的 baseline 符号时退出非零，防止 gate 假通过。

`modules.tsv` 的模块和 package prefix 固定为：

```text
wow-api	wow-api/build/libs/wow-api-*.jar	me/ahoo/wow/api/query/
wow-query	wow-query/build/libs/wow-query-*.jar	me/ahoo/wow/query/
wow-webflux	wow-webflux/build/libs/wow-webflux-*.jar	me/ahoo/wow/webflux/route/query/;me/ahoo/wow/webflux/route/snapshot/;me/ahoo/wow/webflux/route/event/
wow-spring	wow-spring/build/libs/wow-spring-*.jar	me/ahoo/wow/spring/query/
wow-spring-boot-starter	wow-spring-boot-starter/build/libs/wow-spring-boot-starter-*.jar	me/ahoo/wow/spring/boot/starter/query/;me/ahoo/wow/spring/boot/starter/eventsourcing/routing/;me/ahoo/wow/spring/boot/starter/webflux/route/
wow-mongo	wow-mongo/build/libs/wow-mongo-*.jar	me/ahoo/wow/mongo/query/
wow-elasticsearch	wow-elasticsearch/build/libs/wow-elasticsearch-*.jar	me/ahoo/wow/elasticsearch/query/
wow-cosec	wow-cosec/build/libs/wow-cosec-*.jar	me/ahoo/wow/cosec/query/
```

- [ ] **Step 5: 运行 ABI 行为测试并观察 GREEN**

Run: `bash scripts/query-api-abi-test.sh`

Expected GREEN: 所有受控场景通过，输出逐项说明 addition/removal/descriptor/allowlist/missing-input 结果且最终 exit 0。

- [ ] **Step 6: 注册 Gradle 任务并生成当前 baseline**

在根 `build.gradle.kts` 注册：

```kotlin
val queryApiModules = listOf(
    ":wow-api",
    ":wow-query",
    ":wow-webflux",
    ":wow-spring",
    ":wow-spring-boot-starter",
    ":wow-mongo",
    ":wow-elasticsearch",
    ":wow-cosec",
)

tasks.register<Exec>("queryApiDump") {
    dependsOn(queryApiModules.map { "$it:jar" })
    commandLine("bash", "scripts/query-api-abi.sh", "dump")
}

tasks.register<Exec>("queryApiCheck") {
    dependsOn(queryApiModules.map { "$it:jar" })
    commandLine("bash", "scripts/query-api-abi.sh", "check")
}
```

Run: `./gradlew queryApiDump queryApiCheck`

Expected: 八份 baseline 已生成，紧接着 check 为 `BUILD SUCCESSFUL`；`git diff -- config/query-api` 可审查且不含本机绝对路径。

- [ ] **Step 7: 对真实 baseline 运行 gate 与 source fixtures**

Run:

```bash
bash scripts/query-api-abi-test.sh
./gradlew queryApiCheck \
  :wow-query:compileTestKotlin :wow-query:compileTestJava \
  :wow-webflux:compileTestKotlin :wow-spring:compileTestKotlin \
  :wow-spring-boot-starter:compileTestKotlin :wow-mongo:compileTestKotlin \
  :wow-elasticsearch:compileTestKotlin :wow-cosec:compileTestKotlin
```

Expected: script tests 和真实 gate 全部成功；不修改真实 baseline 来模拟失败，破坏性场景只在临时 mini API 中执行。

- [ ] **Step 8: 提交**

```bash
git add build.gradle.kts scripts/query-api-abi.sh scripts/query-api-abi-test.sh config/query-api \
  wow-query/src/test/kotlin/me/ahoo/wow/query/compat wow-query/src/test/java/me/ahoo/wow/query/compat \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/LegacyWebFluxQueryApiSourceCompatibilityTest.kt \
  wow-spring/src/test/kotlin/me/ahoo/wow/spring/query/LegacySpringQueryApiSourceCompatibilityTest.kt \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/query/LegacyQueryStarterApiSourceCompatibilityTest.kt \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/LegacyMongoQueryApiSourceCompatibilityTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/LegacyElasticsearchQueryApiSourceCompatibilityTest.kt \
  wow-cosec/src/test/kotlin/me/ahoo/wow/cosec/query/LegacyCoSecQueryApiSourceCompatibilityTest.kt
git commit -m "test: lock query api compatibility"
```

## Task 2: 定义稳定公开查询值模型

**Interfaces consumed:** `NamedAggregate`、现有 `Projection`、`Sort`、Snapshot/EventStream system fields。

**Interfaces produced:** `QueryTarget`、四种 operation request、`QueryResultShape`、`QueryExpression`、`QueryValue`、budget/scope/error/page 类型。

**Files:**

- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/gateway/QueryTarget.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/gateway/QueryOperation.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/gateway/QueryRequest.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/gateway/QueryResultShape.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/gateway/QueryProjection.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/gateway/QuerySort.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/gateway/QueryPage.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/gateway/QueryBudget.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/gateway/RequestedQueryScope.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/expression/QueryExpression.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/expression/QueryValue.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/error/QueryError.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/gateway/QueryRequestTest.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/expression/QueryExpressionTest.kt`

- [ ] **Step 1: 写不可变性、边界和 JSON round-trip 失败测试**

覆盖：输入 `MutableList`/`MutableMap` 修改后 request 不变；`byte[]` 被复制；逻辑节点拒绝空 child；`Native` 必须声明 backend、template、capability 与 fields；deadline/budget 不接受负数；JSON 不出现驱动类型。

Run: `./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.*"`

Expected: compile failure，因为新类型尚不存在。

- [ ] **Step 2: 实现 target、operation、scope、budget 与错误模型**

公开形态固定为：

```kotlin
enum class QueryDocumentKind { SNAPSHOT, EVENT_STREAM }
enum class QueryOperation { SINGLE, LIST, PAGE, COUNT }

data class QueryTarget(
    val namedAggregate: NamedAggregate,
    val documentKind: QueryDocumentKind
)

data class QueryBudgetHint(
    val timeout: Duration? = null,
    val maxResults: Long? = null,
    val maxCost: Long? = null
)

data class RequestedQueryScope(
    val tenantId: String? = null,
    val ownerId: String? = null,
    val spaceId: String? = null,
    val deletion: DeletionScope = DeletionScope.DEFAULT
)
```

`QueryErrorCode` 至少包含：`INVALID_QUERY`、`POLICY_DENIED`、`POLICY_FAILURE`、`UNSUPPORTED_CAPABILITY`、`BACKEND_NOT_FOUND`、`BACKEND_NOT_READY`、`DEADLINE_EXCEEDED`、`RESULT_VALIDATION_FAILED`、`BACKEND_FAILURE`、`PARTIAL_RESULT`。公开 exception 只携带安全 code、stage 与低基数 reason，不携带表达式值或 authority。

- [ ] **Step 3: 实现 expression 与 `QueryValue`**

```kotlin
sealed interface QueryExpression
sealed interface PortableExpression : QueryExpression
sealed interface CapabilityExpression : QueryExpression

data object MatchAll : PortableExpression
data object MatchNone : PortableExpression
enum class LogicalOperator { AND, OR, NOR }
data class LogicalExpression(
    val operator: LogicalOperator,
    val operands: List<QueryExpression>
) : QueryExpression

data class PortableLogicalExpression(
    val operator: LogicalOperator,
    val operands: List<PortableExpression>
) : PortableExpression

enum class PortableOperator {
    EQ, NE, GT, LT, GTE, LTE, CONTAINS, IN, NOT_IN, BETWEEN, ALL_IN,
    STARTS_WITH, ENDS_WITH, NULL, NOT_NULL, TRUE, FALSE, EXISTS
}

enum class StringComparisonMode { DEFAULT, CASE_SENSITIVE, CASE_INSENSITIVE }

data class PredicateExpression(
    val field: LogicalField,
    val operator: PortableOperator,
    val values: List<QueryValue>,
    val stringComparison: StringComparisonMode = StringComparisonMode.DEFAULT
) : PortableExpression

data class ElementMatchExpression(
    val field: LogicalField,
    val predicate: PortableExpression
) : PortableExpression

data class FullTextExpression(
    val capabilityId: QueryCapabilityId,
    val query: String,
    val fields: Set<LogicalField>
) : CapabilityExpression

data class NativeExpression(
    val capabilityId: QueryCapabilityId,
    val backendId: String,
    val templateId: String,
    val parameters: Map<String, QueryValue>,
    val declaredFields: Set<LogicalField>
) : CapabilityExpression
```

`LogicalField`、`QueryCapabilityId` 使用校验过的 value class。`QueryValue` 是 scalar/decimal/string/instant/enum/list/object/binary/null 的封闭层级；集合在构造器入口复制且公开只读视图。`PredicateExpression.stringComparison` 保留字符串字面操作的默认、显式大小写敏感与忽略大小写三态；非字符串字面操作只能使用 `DEFAULT`。为已形成的 Task 2 source surface 保留三参数 constructor/call 形态，新增维度以默认参数/兼容 overload 演进。

`LogicalExpression` 允许用户表达式在布尔树中组合 portable/capability 节点；`PortableLogicalExpression` 保证 `QueryPolicyResult.mandatoryExpression` 的整个子树只含 portable 节点。两者共享 `AND`/`OR`/`NOR` 归一化规则，但不能互相偷偷降级。

- [ ] **Step 4: 实现 operation-specific request/result**

```kotlin
sealed interface QueryProjection {
    data object All : QueryProjection
    data class Include(val fields: Set<LogicalField>) : QueryProjection
    data class Exclude(val fields: Set<LogicalField>) : QueryProjection
}

enum class QuerySortDirection { ASC, DESC }
data class QuerySort(val field: LogicalField, val direction: QuerySortDirection)
data class QueryPageSpec(val index: Int, val size: Int)

sealed interface QueryResultShape<R : Any> {
    data class Typed<R : Any>(
        val resultType: Class<R>,
        val projection: QueryProjection = QueryProjection.All
    ) : QueryResultShape<R>

    data object Dynamic : QueryResultShape<DynamicDocument>
}

sealed interface QueryRequest {
    val target: QueryTarget
    val expression: QueryExpression
    val requestedScope: RequestedQueryScope
    val budget: QueryBudgetHint
}

sealed interface ResultQueryRequest<R : Any> : QueryRequest {
    val resultShape: QueryResultShape<R>
}

data class SingleQueryRequest<R : Any>(
    override val target: QueryTarget,
    override val expression: QueryExpression = MatchAll,
    override val resultShape: QueryResultShape<R>,
    override val requestedScope: RequestedQueryScope = RequestedQueryScope(),
    override val budget: QueryBudgetHint = QueryBudgetHint(),
    val sort: List<QuerySort> = emptyList()
) : ResultQueryRequest<R>

data class ListQueryRequest<R : Any>(
    override val target: QueryTarget,
    override val expression: QueryExpression = MatchAll,
    override val resultShape: QueryResultShape<R>,
    override val requestedScope: RequestedQueryScope = RequestedQueryScope(),
    override val budget: QueryBudgetHint = QueryBudgetHint(),
    val sort: List<QuerySort> = emptyList(),
    val limit: Int = 0
) : ResultQueryRequest<R>

data class PageQueryRequest<R : Any>(
    override val target: QueryTarget,
    override val expression: QueryExpression = MatchAll,
    override val resultShape: QueryResultShape<R>,
    override val requestedScope: RequestedQueryScope = RequestedQueryScope(),
    override val budget: QueryBudgetHint = QueryBudgetHint(),
    val sort: List<QuerySort> = emptyList(),
    val page: QueryPageSpec = QueryPageSpec(index = 1, size = 10)
) : ResultQueryRequest<R>

data class CountQueryRequest(
    override val target: QueryTarget,
    override val expression: QueryExpression = MatchAll,
    override val requestedScope: RequestedQueryScope = RequestedQueryScope(),
    override val budget: QueryBudgetHint = QueryBudgetHint()
) : QueryRequest

data class QueryPage<R : Any>(
    val items: List<R>,
    val total: Long,
    val consistency: QueryConsistency
)
```

第一阶段 `QueryResultShape.Typed<R>` 使用明确的 `Class<R>` 与逻辑 projection；需要泛型容器 shape 时通过未来新增的并行 descriptor 演进，不在 8.x 原地改签名。`Dynamic` 返回框架不可变 `DynamicDocument`，不暴露 Jackson `JsonNode`。`limit=0` 表示无限流，不在构造器中改写为 10,000。`QueryProjection`、`QuerySort` 和 `QueryPageSpec` 构造时都执行边界校验和防御性复制。

- [ ] **Step 5: 运行测试与 ABI gate**

Run: `./gradlew :wow-api:check queryApiCheck`

Expected: 新符号被允许；baseline 既有符号无删除/descriptor 变化；全部测试通过。

- [ ] **Step 6: 提交**

```bash
git add wow-api/src/main wow-api/src/test
git commit -m "feat: add canonical query contracts"
```

## Task 3: 穷尽降低 43 个旧 Operator 并冻结相对时间

**Interfaces consumed:** 旧 `Condition`/`Operator`/`DeleteConditionGuard`；Task 2 expression/value model。

**Interfaces produced:** 后端无关、单次 materialize、以冻结时间计算的 canonical expression。

**Files:**

- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/expression/LegacyConditionLowerer.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/expression/QueryValueNormalizer.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/expression/RelativeTimeNormalizer.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/expression/ExpressionNormalizer.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/expression/LegacyConditionLowererTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/expression/RelativeTimeNormalizerTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/expression/OneShotIterableTest.kt`

- [ ] **Step 1: 写 43 项枚举覆盖与边界失败测试**

测试构造 `Operator.entries.associateWith { fixture }`，断言 fixture keys 与 `Operator.entries.toSet()` 完全相同；逐项断言 lowering 类型。额外覆盖：空/单子句 AND/OR/NOR、Snapshot `DELETED` 默认 active、EventStream 不加 deletion、`MATCH`→`FullText`、显式 `NativeExpression` 的 `RAW`→`Native`、裸 BSON/ES Query/JSON/String/Map `RAW`→`INVALID_QUERY`、legacy `datePattern`→`INVALID_QUERY`、`ALL_IN` one-shot iterable 只迭代一次。

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.expression.*"`

Expected: compile failure，因为 lowerer 尚不存在。

- [ ] **Step 2: 实现无 `else` 的穷尽 lowerer**

`LegacyConditionLowerer.lower(condition, target, frozenInstant, zoneId)` 中使用 `when (condition.operator)` 明确列出全部 43 个枚举。system operator 映射到固定 `QuerySystemFields`，并按 `QueryDocumentKind` 保留 Snapshot/EventStream 的 ID 差异：Snapshot 的 `ID/IDS` 与 `AGGREGATE_ID/AGGREGATE_IDS` 指向同一聚合身份字段，EventStream 的 record `id` 与 `aggregateId` 分离。普通 operator 映射到 `PredicateExpression`；`CONTAINS`/`STARTS_WITH`/`ENDS_WITH` 将 legacy `ignoreCase` 精确降低为 `StringComparisonMode` 三态。时间 operator 委托 `RelativeTimeNormalizer`；`MATCH` 生成 `FullTextExpression`。`RAW` 只接受 `condition.value is NativeExpression` 并返回该不可变 descriptor；其它 legacy payload 抛出 `QueryException(INVALID_QUERY, NORMALIZE, INVALID_REQUEST)`，不新增 resolver/registry hook，也不把驱动对象交给 `QueryValueNormalizer`。

- [ ] **Step 3: 实现一次 materialize 和 canonical simplification**

`QueryValueNormalizer` 进入任何 `Iterable` 只调用一次 `iterator()`，在同一遍历中完成复制与递归类型化。`ExpressionNormalizer` 只做可证明等价的规则：flatten 同类 AND/OR、去除 AND 中 `MatchAll`、保持 NOR 语义；空 AND→`MatchAll`，空 OR/NOR→`MatchNone`，单子句 NOR 保留 NOR。

- [ ] **Step 4: 用冻结时间实现相对日期 lowering**

每个测试使用 `Clock.fixed` 和明确 `ZoneId`，覆盖 DST 切换日、周/月/年边界、`RECENT_DAYS`/`EARLIER_DAYS` 的零值、负数、非整数与溢出参数。日、周、月范围输出 `[startInclusive, endExclusive)` 的 `Instant` 比较，Normalizer 只接收冻结 `Instant`，不持有或再次读取系统 `Clock`。legacy condition 的显式 `zoneId` 保持为该节点的兼容覆盖值；缺省时使用 invocation `zoneId`。任何存在 `DATE_PATTERN_OPTION_KEY` 的相对时间 condition 都在 Normalize 阶段返回 `INVALID_QUERY`，不生成字符串边界。

- [ ] **Step 5: 运行测试与现有 converter 回归**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.expression.*" --tests "me.ahoo.wow.query.converter.*"`

Expected: 全部通过；现有 converter 行为尚未切换。

- [ ] **Step 6: 提交**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/expression \
  wow-query/src/test/kotlin/me/ahoo/wow/query/expression
git commit -m "feat: normalize legacy query expressions"
```

## Task 4: 从 Jackson 模型推导 Query Schema

**Interfaces consumed:** `AggregateMetadata.state.aggregateType`、Wow 当前 Jackson `ObjectMapper`、Task 2 logical fields/value types。

**Interfaces produced:** immutable `QuerySchemaView`、缓存 resolver、唯一 `QuerySchemaCustomizer` SPI。

**Files:**

- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchema.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaView.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/JacksonQuerySchemaResolver.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaCustomizer.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySystemFields.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/JacksonQuerySchemaResolverTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaCustomizerTest.kt`

- [ ] **Step 1: 写 Jackson 语义失败测试**

fixture 覆盖 `@JsonProperty`、`@JsonIgnore`、全局 naming strategy、Kotlin nullable、enum、nested object、scalar collection、object collection、map、继承和递归类型。分别断言 Snapshot/EventStream framework system fields，且 ignored/property 原名不能被查询。

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.*"`

Expected: compile failure，因为 Schema 类型尚不存在。

- [ ] **Step 2: 实现 immutable schema 与唯一 customizer**

```kotlin
fun interface QuerySchemaCustomizer {
    fun customize(context: QuerySchemaCustomizationContext): QuerySchema
}

interface QuerySchemaResolver {
    fun resolve(target: QueryTarget): Mono<QuerySchemaView>
}
```

Schema field 至少记录 logical path、value kind、nullable、collection kind、nested/object 信息、可排序/可投影/可查询标志。Customizer 只能对逻辑 Schema 增补/收窄字段能力或声明受控 capability binding；不能返回物理 BSON/ES 字段或 authority。

- [ ] **Step 3: 实现 Jackson resolver 与 cache**

使用 Wow 注入的同一个 `ObjectMapper` introspection API，不依赖 `wow-schema`。cache key 必须包含 `QueryTarget` 和 aggregate metadata identity；缓存只保存 fully immutable schema。递归类型使用访问栈截断并产生确定性 schema error，不无限递归。

- [ ] **Step 4: 验证 customizer 固定顺序与冲突失败**

所有 customizer 看见同一基础 schema，集中合并；两个 customizer 对同一 logical field 给出不兼容类型或物理绑定时启动/首次 resolve 失败，不采用最后写入胜出。顺序只影响诊断。

- [ ] **Step 5: 运行测试**

Run: `./gradlew :wow-query:check`

Expected: Jackson/schema/expression 和既有 wow-query 测试全部通过。

- [ ] **Step 6: 提交**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema \
  wow-query/src/test/kotlin/me/ahoo/wow/query/schema
git commit -m "feat: derive logical query schema"
```

## Task 5: 在后端 I/O 前完成结构、Schema 与预算验证

**Interfaces consumed:** canonical expression、operation request、`QuerySchemaView`。

**Interfaces produced:** 验证后的 normalized request；确定性 `INVALID_QUERY`；Planner 可依赖的结构上限。

**Files:**

- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/validation/QueryStructureLimits.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/validation/QueryRequestValidator.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/validation/QueryExpressionValidator.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/validation/QueryBudgetValidator.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/validation/QueryRequestValidatorTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/validation/QueryExpressionValidatorTest.kt`

- [ ] **Step 1: 写 arity/type/field/depth/cardinality 失败测试**

覆盖所有 `PortableOperator` 的合法/非法 arity；unknown/ignored field；scalar 与 collection 类型错配；`ELEM_MATCH` 仅 object collection；projection/sort/capability declared fields；表达式最大深度、节点数、IN 项数、Native parameter bytes；负 page/size/limit；`limit=0` 合法但受 budget。

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.validation.*"`

Expected: compile failure，因为 validator 尚不存在。

- [ ] **Step 2: 实现两阶段验证**

第一阶段只验证 request 结构和硬上限；第二阶段拿到 `QuerySchemaView` 后验证字段、类型、operator、projection/sort/capability fields。错误统一产生 `QueryException(INVALID_QUERY, stage = VALIDATION)`，外部 message 不回显值。

- [ ] **Step 3: 固定预算合并所需值对象**

`QueryBudgetLimit.min(requestHint, systemLimit, policyLimit, backendLimit)` 对 timeout/results/cost 分别取最小有限值；unbounded 只作为内部明确常量，不用 `Long.MAX_VALUE` 参与溢出运算。当前计划只实现纯函数与测试，Policy/backend 值在后续计划接入。

- [ ] **Step 4: 添加 no-backend-I/O 证明 fixture**

建立记录型 validator harness：非法 request 在 resolver supplier 被求值前失败；用计数器断言 resolver invocation 为 0。这是后续 Gateway test 的共用 fixture，不引入真实 Backend。

- [ ] **Step 5: 运行阶段验收**

Run: `./gradlew :wow-api:check :wow-query:check queryApiCheck`

Expected: `BUILD SUCCESSFUL`；ABI 只有新增，无未批准删除；所有新语义测试通过。

- [ ] **Step 6: 提交**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/validation \
  wow-query/src/test/kotlin/me/ahoo/wow/query/validation
git commit -m "feat: validate canonical queries"
```

## Plan 01 完成检查

- [ ] `git status --short` 只显示本计划有意提交之外的既有 `.superpowers/`。
- [ ] `./gradlew :wow-api:check :wow-query:check queryApiCheck` fresh run 通过。
- [ ] `rg -n "QueryConditionContributor|LogicalOperator.NOT|org.bson|co.elastic|springframework" wow-api/src/main/kotlin/me/ahoo/wow/api/query wow-query/src/main/kotlin/me/ahoo/wow/query/{expression,schema,validation}` 无违规命中。
- [ ] `Operator.entries` coverage test 证明 43 项全部有 fixture，lowerer `when` 没有 `else`。
- [ ] 本计划未改 Spring、旧 QueryService runtime、后端 converter、Filter 或文档站。
- [ ] 执行 `superpowers:verification-before-completion` 后，再开始 Plan 02。
