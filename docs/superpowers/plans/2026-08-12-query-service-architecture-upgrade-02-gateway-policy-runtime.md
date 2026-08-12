# Query Gateway 与 Policy 运行时 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于 Plan 01 的 canonical model，实现每次订阅隔离的 Invocation、唯一 `QueryPolicy` 扩展 SPI、版本化 Backend consumer contract、Planner 与 `QueryGateway` 全流水线，并以记录型 Backend 证明行为；本阶段只做 additive Spring 装配，不切换旧入口。

**Architecture:** `QueryGateway` 使用 `Mono.defer`/`Flux.defer` 为每次 subscription 建立 immutable invocation。所有 Policy 看同一 `QueryPolicyContext`，集中以固定规则合并；Resolver 冻结唯一 Backend snapshot，internal Planner 创建公开只读 `QueryPlanV1`，Backend operation-specific 执行。Spring/非 Spring 注册都在 Gateway 构造时固定 System + custom policy 快照，不支持运行时动态注册。

**Tech Stack:** Kotlin、Reactor、Micrometer、Spring Framework / Spring Boot、JUnit Jupiter、Reactor Test、FluentAssert。

## Global Constraints

- 必须先完成 [Plan 01](2026-08-12-query-service-architecture-upgrade-01-contract-semantic-core.md) 的全部 gate。
- 不实现 `QueryConditionContributor`、Filter adapter、runtime Filter detection 或可变 `QueryContext.attributes`。
- `QueryPolicy` 只注入调用方不可移除的 mandatory portable expression 与权限/预算约束；可覆盖默认值继续属于 request/DSL/领域 facade。
- Policy 评估在 Backend Resolver 前完成；`POLICY_DENIED`、`POLICY_FAILURE`、`DEADLINE_EXCEEDED` 时 Backend 调用计数必须为 0。
- System policy 始终存在且不可替换；不得对所有 capability 预先产生 blanket `DENY`。
- 本阶段不得修改旧 `QueryService`、WebFlux handler、Mongo/ES converter 或 storage routing；真实后端留给 Plan 03。
- 所有新增稳定 SPI 进入 ABI gate；实现类、combiner、planner、recording backend 保持 `internal` 或 test source。
- 唯一准备顺序为 `Admission → Structure Validation → Schema Resolve → Normalize → Schema Validation → Policy → Policy-output Validation → Backend Resolve → Plan → Execute → ResultPolicy`；Schema Resolver 不是 Backend Resolver。
- 所有 timeout 从 subscription 的同一 frozen instant 追溯：request/system 先形成 admission deadline，policy/backend 后形成 effective deadline；后续阶段不重读 Clock。

---

## Task 0: 收敛预检发现的公开错误契约

**Interfaces consumed:** Plan 01 新增但尚未发布的 `QueryErrorCode`；最终规格错误表。

**Interfaces produced:** 单义的 backend-unavailable 与 incomplete-stream 错误集合。

**Files:**

- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/error/QueryError.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/error/QueryErrorContractTest.kt`
- Modify by authoritative dump only: `config/query-api/wow-api-8.x.baseline`

- [ ] **Step 1: 写精确错误码集合失败测试**

断言公开 enum 精确为最终规格的十个 code；`BACKEND_NOT_FOUND` 与 `PARTIAL_RESULT` 不得存在。先在旧实现上得到集合不匹配 RED。

- [ ] **Step 2: 删除未发布的同义 code 并重建 ABI baseline**

未注册/未就绪 backend 统一使用 `BACKEND_NOT_READY`；首项后流失败统一使用 `INCOMPLETE_RESULT`。这是 Plan 01 新增面在首次发布前的契约纠正，不加入 approved-removals。运行权威 `queryApiDump`，审查 baseline 只删除这两个 enum field，并保留所有既有 8.x symbol。

- [ ] **Step 3: 运行验证并提交**

Run: `./gradlew :wow-api:check queryApiDump queryApiCheck`

```bash
git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/error \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/error \
  config/query-api/wow-api-8.x.baseline
git commit -m "fix: align query error contract"
```

---

## Task 1: 建立每次 subscription 独立的 Invocation 与 Admission

**Interfaces consumed:** Plan 01 request/scope/budget/schema/normalizer；服务端可信 authority provider。

**Interfaces produced:** immutable `QueryInvocationScope`、provenance、frozen time/deadline、`QueryAdmission`。

**Files:**

- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/QueryAuthorityView.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/QueryProvenance.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/QueryInvocationScope.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/QueryInvocationSeed.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/QueryInvocation.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/QueryInvocationFactory.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/QueryAdmission.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/QueryAdmissionContext.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/QueryAuthorityProvider.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/invocation/DefaultQueryAdmission.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/invocation/DefaultQueryAdmissionTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/invocation/QuerySubscriptionIsolationTest.kt`

- [ ] **Step 1: 写 subscription isolation 与 authority 边界失败测试**

同一个 cold request publisher 订阅两次，断言两次 correlation id、frozen instant、deadline 和 scope object identity 不同；同一次 subscription 的 Normalize/Policy/Planner 观察到完全相同的 frozen instant。调用方 request 中 tenant/owner/space 只能进入 `CALLER_REQUEST`，不能成为 trusted authority。

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.invocation.*"`

Expected: compile failure，因为 Invocation 类型尚不存在。

- [ ] **Step 2: 定义稳定不可变 context view**

```kotlin
enum class QueryProvenance {
    CALLER_REQUEST,
    TRUSTED_AUTHORITY,
    MANDATORY_POLICY,
    SYSTEM_METADATA,
    LEGACY_ENRICHMENT
}

data class QueryAuthorityView(
    val subjectId: String?,
    val tenantId: String?,
    val ownerId: String?,
    val spaceIds: Set<String>,
    val permissions: Set<String>
)

data class QueryInvocationScope(
    val trustedAuthority: QueryAuthorityView,
    val requestedScope: RequestedQueryScope,
    val correlationId: String
)
```

集合构造时复制。Admission 产出的 internal `QueryInvocationSeed` 持有 raw immutable request、scope、frozen `Instant`、`ZoneId`、admission deadline 和 request/system admission budget；后续 Schema/Normalize 阶段才创建 `QueryInvocation`，加入 operation、schema、normalized expression 和 provenance map。Policy/backend 产生的 effective budget/deadline 只进入 Planner 创建的 plan。两者都不得提供 mutable map 或替换 request/expression 的 setter。

- [ ] **Step 3: 实现 `QueryAdmission` 与默认实现**

```kotlin
fun interface QueryAdmission {
    fun admit(context: QueryAdmissionContext): Mono<QueryInvocationScope>
}
```

`QueryAdmissionContext` 固定包含 raw immutable `QueryRequest`、显式 `QueryOperation`、入口 `QueryProvenance` 与由 `QueryInvocationFactory` 为本次 subscription 生成的只读 `correlationId`。`correlationId` 只是作用域标识，不授予 Admission 读取时钟或预算的权限；Admission 必须将它原样带入返回的 `QueryInvocationScope`，不得重新生成或从隐式 Reactor Context 获取。`DefaultQueryAdmission` 由服务器装配可信 `QueryAuthorityProvider`，从服务器安全上下文读取 authority；request 只贡献 requested scope。`QueryAdmission` 只解析并校验可信 scope，返回 `QueryInvocationScope`；它不冻结时间，也不创建 seed。Admission port 不读取 Spring/HTTP 类型。

- [ ] **Step 4: 用 `defer` 证明时间与状态不跨订阅复用**

增加 `QueryInvocationFactory.admit(request, operation, entryProvenance)` 返回 cold `Mono<QueryInvocationSeed>`；它在 `Mono.defer` 内唯一冻结 Clock、Zone、correlation、request/system `admissionBudget` 与绝对 `admissionDeadline`，再调用 `QueryAdmission` 获得 scope。用可推进 fake clock 断言 subscription 间变化、subscription 内稳定；取消 Admission 时不调用 Schema/Backend resolver。Gateway 后续阶段用同一 seed 构造 `QueryInvocation`，不再次读取 clock。`QueryInvocation` 保存 admission budget/deadline；Policy 与 Backend 限制合并后的 effective budget/deadline 只进入最终 plan，不通过 setter 回写 invocation。

- [ ] **Step 5: 运行测试并提交**

Run: `./gradlew :wow-query:check queryApiCheck`

Expected: 全部通过；ABI 只有新增。

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/invocation \
  wow-query/src/test/kotlin/me/ahoo/wow/query/invocation
git commit -m "feat: add query admission scope"
```

## Task 2: 实现唯一 QueryPolicy SPI 与确定性组合器

**Interfaces consumed:** `QueryInvocation`、`QuerySchemaView`、canonical expression、budget types。

**Interfaces produced:** 稳定 `QueryPolicy`/context/result/constraints；System policy；fail-closed combination。

**Files:**

- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/QueryPolicy.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/QueryPolicyContext.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/QueryPolicyResult.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/QueryPolicyConstraints.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/QueryFieldAccess.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/CapabilityDecision.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/QueryPolicyException.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/SystemQueryPolicy.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/DefaultQueryPolicyChain.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/QueryPolicyDescriptor.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/QueryPolicyResultShape.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/policy/DefaultQueryPolicyChainTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/policy/SystemQueryPolicyTest.kt`

- [ ] **Step 1: 写固定组合矩阵失败测试**

覆盖：mandatory expressions 全部 `AND`；field access 交集；budget 各维度取最小；capability `DENY` 胜出；无 `DENY` 且至少一个 `GRANT` 才允许；全 `ABSTAIN` 拒绝；Policy 顺序变化不改变最终语义；所有 Policy 接收到同一个 context object；System policy 不可被 custom list 替换。

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.policy.*"`

Expected: compile failure，因为 Policy SPI 尚不存在。

- [ ] **Step 2: 实现规格固定的公开 SPI**

```kotlin
fun interface QueryPolicy {
    fun evaluate(context: QueryPolicyContext): Mono<QueryPolicyResult>
}

data class QueryPolicyResult(
    val mandatoryExpression: PortableExpression = MatchAll,
    val constraints: QueryPolicyConstraints = QueryPolicyConstraints.NONE
)

data class QueryPolicyConstraints(
    val fieldAccess: QueryFieldAccess = QueryFieldAccess.UNRESTRICTED,
    val capabilityAccess: Map<QueryCapabilityId, CapabilityDecision> = emptyMap(),
    val maxBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED
)
```

`QueryPolicyContext` 只暴露 target、operation、normalized expression、result shape view、invocation scope、schema view、request budget、frozen instant/zone。公开 sealed `QueryPolicyResultShape` 显式区分 `Typed`、`Dynamic` 与 `Count`，COUNT 不使用 null/伪造类型表达。它不暴露 Spring、HTTP、driver、mutable attributes、Backend 或 replace-query 方法。

- [ ] **Step 3: 实现 deterministic fail-closed 组合器**

`DefaultQueryPolicyChain` 在构造时复制 `[SystemQueryPolicy] + sorted custom policies`；排序只用于稳定评估/日志。使用 `concatMap` 逐个评估，但每个都接收同一个原始 context。对每个结果先验证 mandatory expression 仅为 portable、字段/预算约束有效，再集中合并。

错误映射固定为：

- `QueryPolicyDeniedException(reasonCode)` → `POLICY_DENIED`；
- `Mono.empty()`、unexpected exception、unknown field、illegal result → `POLICY_FAILURE`；
- deadline 先到 → `DEADLINE_EXCEEDED`，stage=`POLICY`；
- 所有错误都不得 `onErrorResume` 为 `MatchAll`。

- [ ] **Step 4: 实现 System policy 不变量**

Snapshot 的 `DEFAULT`/`ACTIVE` deletion scope 注入 active portable predicate；EventStream 不注入。显式请求 `DELETED`/`ALL` 时，System policy 先检查 trusted authority 的稳定删除查询 permission：允许则生成对应 predicate/`MatchAll`，否则 `POLICY_DENIED`，不能把 active 与 deleted 同时 `AND` 成空结果。Gateway 的 user expression 不得预先包含兼容 `DeleteConditionGuard`；Plan 04 的 legacy mapper 必须先把 deletion intent 提取为 `RequestedQueryScope.deletion`，最终 deletion predicate 只在这里注入。System policy 同时提供 schema field baseline 和系统预算上限，并让组合器在请求 capability 但无任何 grant 时拒绝；它本身不输出 blanket capability `DENY`。

- [ ] **Step 5: 验证 Backend 尚未被调用**

测试给 resolver supplier 计数，在 denied/failure/timeout/invalid mandatory expression 情况均断言 0；成功组合才为 1。外部 exception message 不含 authority、mandatory expression、bean name、堆栈或条件值。

- [ ] **Step 6: 运行测试并提交**

Run: `./gradlew :wow-query:check queryApiCheck`

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/policy \
  wow-query/src/test/kotlin/me/ahoo/wow/query/policy
git commit -m "feat: add composable query policies"
```

## Task 3: 在 wow-test 发布 QueryPolicyTestKit

**Interfaces consumed:** Task 2 稳定 Policy SPI；Wow test DSL 与 FluentAssert。

**Interfaces produced:** 第三方 Policy 开发者可复用的 context builder 与组合/拒绝/跨 target 测试工具。

**Files:**

- Modify: `test/wow-test/build.gradle.kts`
- Create: `test/wow-test/src/main/kotlin/me/ahoo/wow/test/query/QueryPolicyTestKit.kt`
- Create: `test/wow-test/src/main/kotlin/me/ahoo/wow/test/query/QueryPolicyContextBuilder.kt`
- Create: `test/wow-test/src/test/kotlin/me/ahoo/wow/test/query/QueryPolicyTestKitTest.kt`

- [ ] **Step 1: 写 test kit 自测**

覆盖 target applicable/not-applicable、tenant match/mismatch、mandatory expression、field intersection、capability grant/deny/abstain、frozen time 和 Policy error mapping。Builder 默认值必须显式且可读，不从系统当前时间或 Spring context 隐式取值。

- [ ] **Step 2: 添加 `wow-test -> wow-query` API 依赖并先验证任务图**

```kotlin
dependencies {
    api(project(":wow-query"))
}
```

Run: `./gradlew :wow-test:compileKotlin :wow-tck:compileKotlin :wow-query:compileTestKotlin --dry-run`

Expected: 无 circular dependency；若 Gradle 报 task cycle，停止，不移动 Policy SPI 到错误模块。最小修正是将 `wow-query` 测试中使用的少量 `wow-tck.mock` fixture 复制为 `wow-query/src/testFixtures` 本地 fixture，并删除 `wow-query` 对 `wow-tck` 的 test dependency，再重新验证。

- [ ] **Step 3: 实现 TestKit**

```kotlin
class QueryPolicyTestKit(
    private val policy: QueryPolicy,
    private val context: QueryPolicyContext
) {
    fun evaluate(): Mono<QueryPolicyResult>
    fun expectDenied(reasonCode: String): Mono<Void>
    fun expectMandatory(expected: PortableExpression): Mono<Void>
}
```

TestKit 通过公开 SPI 执行，不反射访问 internal combiner；组合测试另由 `DefaultQueryPolicyChainTest` 覆盖。提供 immutable builder 构造 target、authority、scope、schema、expression、budget、instant/zone。

- [ ] **Step 4: 运行模块验证并提交**

Run: `./gradlew :wow-test:check :wow-tck:check :wow-query:check`

Expected: `BUILD SUCCESSFUL` 且没有依赖循环。

```bash
git add test/wow-test wow-query/build.gradle.kts wow-query/src/testFixtures
git commit -m "test: add query policy test kit"
```

只 stage 实际存在且本任务有意修改的路径；若未创建 `src/testFixtures`，不要把它写进 `git add`。

## Task 4: 定义稳定 Backend consumer SPI 与 internal Planner

**Interfaces consumed:** authorized expression/result shape/effective budget；resolved schema。

**Interfaces produced:** `QueryBackend`、descriptor/resolver/readiness、只读 `QueryPlanV1`；recording backend test double。

**Files:**

- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/backend/QueryBackend.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/backend/QueryBackendDescriptor.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/backend/QueryBackendResolver.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/backend/QueryBackendReadiness.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/backend/ResolvedQueryBackend.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/plan/QueryPlanV1.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/plan/DefaultQueryPlanner.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/plan/QueryPlanValidator.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/backend/RecordingQueryBackend.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/plan/DefaultQueryPlannerTest.kt`

- [ ] **Step 1: 写 descriptor/capability/plan 失败测试**

覆盖 document kind、plan version、portable operator、capability、max budget、readiness；unsupported capability 在 execute 前失败；sort 不稳定时 Planner 自动追加唯一稳定 system id；重复/冲突 sort 失败；应用无法通过公开构造器创建或复制 plan。

- [ ] **Step 2: 定义 operation-specific public Backend SPI**

```kotlin
interface QueryBackend {
    val descriptor: QueryBackendDescriptor
    fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R>
    fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R>
    fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>>
    fun count(plan: CountQueryPlanV1): Mono<Long>
    fun readiness(): Mono<QueryBackendReadiness>
}

interface QueryBackendResolver {
    fun resolve(target: QueryTarget): Mono<ResolvedQueryBackend>
}
```

`QueryBackendDescriptor` 固定 backend id、document kinds、plan versions、portable operators、capability ids 与 backend budget limit。`ResolvedQueryBackend` 绑定 backend、descriptor、route identity 与本次 resolve 唯一取得的 immutable `readinessSnapshot`，构造时校验 descriptor 与 backend 一致。Resolver 每次 invocation 只调用一次 readiness；Planner 与执行器不得重读 backend readiness。

- [ ] **Step 3: 用 sealed consumer interfaces 定义只读计划**

`QueryPlanV1` 与四个 operation-specific plan 是 public sealed consumer interfaces；实际 immutable implementation 必须是 Planner/private factory 内的 **private nested classes**，不能使用会编译成 JVM-public 的 top-level Kotlin `internal class`。第三方 Backend 只能读取接口，Kotlin/Java source 都不能新增 permitted implementation，也没有 public builder/constructor/copy。用 Kotlin/Java compile-negative fixture 与 `javap`/ABI probe 证明无 public implementation/constructor/copy。共享 target、canonical secured expression、authorized result shape、stable sort、从 frozen instant 追溯形成的 absolute effective deadline/effective budget、correlation/route identity；各接口只暴露 operation 所需的 limit/page 字段。不得出现 authority、Spring、wire DTO、BSON、ES DSL。

- [ ] **Step 4: 实现 Planner 能力与预算协商**

顺序固定：descriptor/readiness snapshot validation → portable operator support → capability backend/config/policy 三方许可 → projection/sort field binding → stable sort → effective budget → plan validation。Planner 只消费 Policy 合并结果，不再看旧 `Condition` 或旧 `Operator`。

- [ ] **Step 5: 实现 recording backend 测试替身**

记录每个 operation 收到的 plan、subscription/cancel/terminal 次数；可配置 success/error/never/partial list。后续 Gateway 测试用它证明执行次数、资源生命周期和错误映射，不在 main source 暴露 fake backend。

- [ ] **Step 6: 运行测试并提交**

Run: `./gradlew :wow-query:check queryApiCheck`

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/{backend,plan} \
  wow-query/src/test/kotlin/me/ahoo/wow/query/{backend,plan}
git commit -m "feat: define versioned query backend spi"
```

## Task 5: 实现 QueryGateway 唯一流水线与结果策略

**Interfaces consumed:** Admission、Normalizer、Policy chain、Resolver、Planner、Backend SPI。

**Interfaces produced:** 稳定 `QueryGateway`/factory；`DefaultQueryGateway`；ResultPolicy；metrics/error stage。

**Files:**

- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryGateway.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryGatewayFactory.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryGatewayConfiguration.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/DefaultQueryGateway.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/result/ResultPolicy.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/result/ResultPolicyContext.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/result/DefaultResultPolicyChain.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/metrics/QueryGatewayMetrics.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/DefaultQueryGatewayTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewayLifecycleTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewayPolicyConsistencyTest.kt`

- [ ] **Step 1: 写四 operation 与完整阶段顺序失败测试**

```kotlin
interface QueryGateway {
    fun <R : Any> single(request: SingleQueryRequest<R>): Mono<R>
    fun <R : Any> list(request: ListQueryRequest<R>): Flux<R>
    fun <R : Any> page(request: PageQueryRequest<R>): Mono<QueryPage<R>>
    fun count(request: CountQueryRequest): Mono<Long>
}
```

记录 stage events，断言 Admission → Structure Validation → Schema Resolve → Normalize → Schema Validation → Policy → Policy-output Validation → Backend Resolve → Plan → Execute → ResultPolicy；Schema resolver 与 Backend resolver 使用不同事件/计数，Policy 错误时 Backend resolver 为 0。四方法都走同一私有 orchestration，不复制安全逻辑。重复订阅执行两套 invocation；未订阅不执行任何阶段。

- [ ] **Step 2: 写原子/流式与错误语义失败测试**

`single/page/count` 只有 backend 完成且 result validation/ResultPolicy 全部成功才发射；`list` 逐项背压发射。首项前失败保留具体错误；首项后 decode/policy/backend 失败映射 `INCOMPLETE_RESULT`、保留安全 cause code 并取消上游。deadline/cancel 传播到 backend，terminal signal 只记录一次。

- [ ] **Step 3: 实现 `DefaultQueryGateway`**

所有 public 方法以 `defer` 开始；用一个 internal `prepare(request, operation)` 返回 resolved invocation + plan，四个 operation 只在最后选择 Backend 对应方法。不得缓存 invocation publisher，不得 `block()`/`subscribe()`，不得错误恢复为空结果。

- [ ] **Step 4: 实现 ResultPolicy 与字段读取防线**

ResultPolicy 接收只读 context 与单个 decoded value，支持脱敏/审计/后置校验；projection field access 已在 Planner 前收窄，ResultPolicy 不能成为唯一字段安全边界。所有 policy 在 Gateway 构造时复制为 immutable list；空 publisher/非法 null → `RESULT_VALIDATION_FAILED`。

- [ ] **Step 5: 实现低基数 metrics**

只记录 operation、document kind、backend id、outcome、error code、capability id、policy descriptor、legacy facade flag；不得记录 Native payload、expression、字段值、authority 或高基数 aggregate id。测试用 `SimpleMeterRegistry` 断言成功/失败/取消各一次。

- [ ] **Step 6: 实现 non-Spring factory**

`QueryGatewayFactory.create(configuration: QueryGatewayConfiguration)` 的 configuration 显式持有 immutable custom policy/result policy list、admission、schema resolver、backend resolver、clock、zone、无默认值的 structure limits、显式 system budget limit 和 capability config；框架始终加入 System policy。传入 list 后外部修改不得影响已创建 Gateway。request/system 形成 admission deadline；Policy/Backend 后按 frozen instant 追溯形成 effective deadline，形成时已过期立即失败。

- [ ] **Step 7: 运行测试并提交**

Run: `./gradlew :wow-query:check queryApiCheck`

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query \
  wow-query/src/test/kotlin/me/ahoo/wow/query
git commit -m "feat: add planned query gateway"
```

使用 `git diff --cached --name-only` 排除旧 Filter、converter 与无关文件后再提交。

## Task 6: Additive Spring Boot 注册，不切换旧入口

**Interfaces consumed:** `QueryGatewayFactory`、Spring `ListableBeanFactory`/`Ordered`、现有 query auto-configuration。

**Interfaces produced:** 唯一 `QueryGateway` Bean；System + ordered custom Policy immutable snapshot；缺 backend 显式错误 resolver。

**Files:**

- Create: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryGatewayAutoConfiguration.kt`
- Create: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryGatewayProperties.kt`
- Create: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryGatewayAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: 写 Spring context 失败测试**

覆盖：无 custom policy 仍有 System policy；多个 `@Order` policy 被固定成快照；用户不能以自定义 bean 替换 System policy；自定义 `QueryGateway` 时 auto-config back off；尚无 backend 时调用返回 `BACKEND_NOT_READY` 而不是空结果；旧 QueryService/Filter beans 本阶段原样存在。

- [ ] **Step 2: 实现独立 auto-configuration**

不要在现有 `QueryAutoConfiguration` 中立刻删除 filter wiring。新配置通过 `@ConditionalOnMissingBean(QueryGateway::class)` 创建 Gateway；注入 `List<QueryPolicy>` 和 `List<ResultPolicy>` 后按 `AnnotationAwareOrderComparator` 排序并复制。注册层生成低基数 `QueryPolicyDescriptor`，不修改 SPI。Task 6 必须明确装配 schema resolver、authority provider（无安全扩展时为显式 anonymous authority）、clock/zone、structure limits、system budget/capability config 与 metrics。System policy 只由 factory 内部追加一次，不注册为会再次进入 custom `List<QueryPolicy>` 的普通 bean。

Spring 默认配置为显式、可覆盖的生产值：`maxDepth=64`、`maxNodes=10000`、`maxMembershipItems=10000`、`maxNativeParameterBytes=1048576`；system timeout/results/cost 默认 `UNBOUNDED`，保持 legacy 无隐式结果量/deadline 上限；capability 默认禁用，必须配置启用并由 Policy grant。properties 绑定与构造测试锁定这些值，配置的 0/负结构上限启动失败。

- [ ] **Step 3: 实现明确 unavailable resolver**

当 Plan 03 尚未注册 `QueryBackendResolver` 时使用只返回 `QueryException(BACKEND_NOT_READY, BACKEND_RESOLUTION, BACKEND_UNAVAILABLE)` 的 resolver；禁止 NoOp/empty fallback。优先把它作为 Gateway 构造时的 missing-bean fallback，避免与 Plan 03 的真实 resolver 形成多 bean；若注册 fallback bean，必须以明确 ordering/conditional 在真实 resolver 出现时 back off。

- [ ] **Step 4: 运行阶段验收并提交**

Run: `./gradlew :wow-query:check :wow-test:check :wow-tck:check :wow-spring-boot-starter:check queryApiCheck`

Expected: 所有测试通过；旧 runtime 尚未切换；新 Gateway 可以由 recording/custom resolver 验证。

```bash
git add wow-spring-boot-starter/src/main wow-spring-boot-starter/src/test
git commit -m "feat: auto configure query gateway"
```

## Plan 02 完成检查

- [ ] fresh run：`./gradlew :wow-query:check :wow-test:check :wow-tck:check :wow-spring-boot-starter:check queryApiCheck`。
- [ ] `rg -n "QueryConditionContributor|register\(|unregister\(|MutableMap|QueryContext" wow-query/src/main/kotlin/me/ahoo/wow/query/{invocation,policy,backend,plan,result}` 无禁用设计。
- [ ] Policy 全部错误路径的 recording resolver/backend 调用数为 0。
- [ ] 两次 subscription isolation 测试证明时间、deadline、correlation 与 scope 不共享。
- [ ] `QueryPlanV1` public API 只有 sealed consumer interfaces；无 public implementation/builder/copy，也不含 driver/Spring/wire DTO/authority。
- [ ] 旧 QueryService、Filter、WebFlux、Mongo、Elasticsearch runtime 尚未切换。
- [ ] 执行 `superpowers:verification-before-completion` 后，再开始 Plan 03。
