# 查询模块整体重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development with superpowers:dispatching-parallel-agents for disjoint work; shared interface changes remain sequential. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持 QueryGateway 调用 API 的前提下，完成职责清晰、无隐式降级的查询模块重构。

**Architecture:** Gateway 编排受管流程，Schema 保存事实，Backend 完成原生编译和执行。QueryFilter 仅准备逻辑请求；Mask、物化与终止观察属于固定管道。HTTP 负责传输限额、超时和输出。

**Tech Stack:** Kotlin/JVM 17、现有 Jackson、Reactor、JUnit/MockK/Reactor Test、现有 TCK/Testcontainers/JMH；不新增依赖或模块。

**Spec:** [整体设计](2026-09-08-query-architecture-redesign.md)，以第 4 节请求扩展、第 5 节结果管道、第 11 节验收为当前合同。[对抗报告](2026-09-08-query-architecture-adversarial-review.md)中 next 中间件属于已退出方案。

**当前状态：** 任务 1–7 的生产迁移及历史验收已完成。整体重构验收见[PR 前深度复核验收](2026-09-08-query-architecture-adversarial-review.md#pr-readiness)，后续通用策略、EventStream 与 HTTP 接入见[QueryPolicy 验收](2026-09-08-query-architecture-adversarial-review.md#query-policy-acceptance)。各节测试数量、阶段性“最终”及性能产物只指记录时的代码，不跨提交累加或扩大保证范围。

## Global Constraints

- 保留 QueryGateway 包名、泛型、继承关系、十个方法的名称/参数名称/类型/空值约束和返回形状；保持查询 DTO wire 形状。
- 内部 Schema、Backend、Filter SPI 允许破坏性变更；不保留永久双模式或桥接层。旧 `Condition` 兼容栈按用户约定保留至 10.0.0，独立于内部 SPI 清理。
- Source 声明三态只存在输入阶段；原生能力、公共许可、HTTP 元数据视图分离。
- 每订阅固定一份 Schema；首次执行性 I/O 前完成全部可推导的公共和原生静态检查。
- 不新增 PreparedQuery、通用计划、规则引擎、插件监管状态机或结果缓存。
- 不修改版本、发布流程。实现阶段保留本地差异；后续按用户明确授权完成深度复核、提交 PR 并等待 GitHub Codex review，不合并或发布。
- 使用现有 worktree；实施前检查 HEAD、工作区差异和 AGENTS.md，保留用户其他改动。
- 以失败测试锁定行为再实现；失败必须来自目标行为而非配置/容器故障。文档原型不能直接计入生产验收。

## 文件组织与依赖

下文路径以仓库根为基准；同一任务列出的目录限定为本次 query 相关文件，不授权顺带重构其他模块。

| 单元 | 文件与职责 |
|---|---|
| 稳定入口 | `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryGateway.kt`：十个 API 及 mono/flux 编排 |
| Schema 数据 | `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt`、`QuerySchemaDeclaration.kt`、`QuerySchemaMerger.kt`：事实、输入合并及索引 |
| 公共语义 | 同目录 `QuerySchemaValidation.kt`、`QueryValueDomains.kt`、`QuerySchemaBindings.kt`：公共检查、值域与逻辑路径助手；旧三个 resolver 文件已删除 |
| 扩展 | `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryFilter.kt`、`QueryContext.kt`：请求 prepare 和只读上下文 |
| 执行 SPI | `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryBackend.kt`：六形态逻辑 Query + Schema |
| 模型语义 | `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/`、`event/`：保留模型差异，复用执行链 |
| 原生后端 | `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/`、`wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/` |
| HTTP | `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/`：范围提取、限额、超时、metadata 输出 |

以下工作包按互斥文件并行，共享合同顺序冻结。任务 2–4 作为同一次内部切换完成，未创建临时兼容 SPI 或发布中间状态。

## 并行工作包与文件所有权

并行依据是稳定合同和互斥写入范围，不能把章节号直接当可并行任务。全模块 Gradle 和集成测试由协调者汇总安排；子代理可以运行各自模块的聚焦测试。接口改动前先通知所有消费该接口的任务。

| 工作包 | 当前状态 | 写入范围 | 完成条件 / 依赖 |
|---|---|---|---|
| B0 兼容基线 | 完成 | 旧调用方/JSON/描述符及 API 测试 | 证据冻结，后续只比较不覆盖 |
| S0 原生类型集合 | 完成 | QueryFieldBinding、两 Adapter 与消费者 | 保留联合与未知，已有红绿及实库证据 |
| M1 Mongo 投影准入 | 已实现并独立审查 | MongoProjectionCompiler、AbstractMongoQueryBackend 及相应测试 | 非法组合零 I/O；父子冗余规范化；合法 _id 例外；paged 先准备完整再 count |
| E1 ES 完整性 | 已实现并独立审查 | ES Backend/pager/aggregation 响应消费与对应测试 | search/count/聚合拒绝超时/分片失败；无成功部分末页 |
| S1 共享 Schema 合同 | 已实现，核心 check 与独立反例复验通过 | 数据定义、lookup、保护关系、公共规则和 metadata 函数 | 具体类型/函数冻结后，才分派 Source/Adapter 消费迁移 |
| S2 声明生成 | 完成，251 项单测通过 | wow-schema query 声明生成与测试 | 动态值和 union 不丢失，三态合并与 allOf 约束交集明确定义 |
| S3 公共规则 | 已实现，核心及值域独立复核通过 | wow-query 公共查询校验与测试 | Schema 不执行 Query；指定搜索字段不降级 |
| B1 Backend SPI | 已完成，包装退出且 API 基线通过 | QueryBackend 及所有实现/调用者 | 六方法同批改签名，不留 ResolvedQuery 桥 |
| G1 受管管道 | 已实现并验证 | Gateway/Filter/授权/Mask、QueryScope、旧 HTTP Guard 退出，以及直接注册消费者 | 请求-only prepare；强制步骤不可排序替换；保持 QueryGateway API |
| H1 HTTP | 已实现，299 项单测通过 | WebFlux Handler、范围/限额/超时/响应 | Schema 等待计入超时，实际输出受限，metadata 只派生 |
| C1 外部消费 | 完成，文档构建及测试通过 | OpenAPI/生成助手/客户端及相关文档 | 编译、wire 与中英文迁移说明一致 |
| V1 整体验收 | 完成，含冻结 API、实库、独立反例及性能配对/反序/长预热复核 | 只新增验证与必要修正 | 实库、冻结 API、性能基准和独立最终审查 |

M1/E1 不等待共享 SPI 的名称变化：原生投影/响应语义已确定，使用当前参数包装实现，之后 B1 只迁移接口。QueryModelSchema 本身的形状演进不影响 `(query, schema)` 这个已冻结签名，因此 B1 不再等待 S1 完整实现；必须一次迁完真实调用方，不借临时桥接通过编译。不得让它们私自修改 Schema/Gateway，或用改共享校验掩盖后端问题。

G1/H1 的直接 SPI 消费迁移按同一集成批次闭合：G1 负责 QueryScope 和旧 Guard 退出时的 HTTP 接线；H1 负责完整 Handler 迁移与传输合同验证，两者未共同通过前不得称执行管道切换完成。

每工作包交付：文件清单、失败测试与修复后命令、实际结果计数、未覆盖范围。协调者检查差异，再派独立审查者按规约与代码质量复核；不以子代理自报完成替代验证。任一发现只回到所属工作包修复，不扩大其他代理写入范围。

## 任务 1：冻结 API 与可比较基线

**Files:**
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewayApiTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/QueryGatewaySubscriptionTest.kt`
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/CursorPageTest.kt`、`PagedListTest.kt`
- Evidence: `build/query-architecture-migration/`（不提交）

**Interfaces:** 消费当前 QueryGateway；产出旧调用方 class 文件、API 描述符、DTO JSON 基线和测试结果，不产出新运行时接口。

- [x] 记录当前 HEAD 与工作区；将无第三方状态的 API 正常调用样例编译成独立 fixture，保存旧接口编译产物。样例调用全部十个方法，至少包括 Kotlin 命名参数 `single(query = query)` 和 Java 接口调用；不实例化内部实现构造器。
- [x] 保存 API 描述符和查询/结果 JSON 固定样例；Snapshot 与 EventStream 分别覆盖 typed/dynamic，排除原生绑定进入 wire。
- [x] 运行当前基线，失败项按现有问题与环境问题区分。

```bash
./gradlew :wow-query:test --tests '*QueryGatewayApiTest' --tests '*QueryGatewaySubscriptionTest' --console=plain
./gradlew :wow-api:test --tests '*CursorPageTest' --tests '*PagedListTest' --console=plain
```

- [x] 锁定错误修复以外的正常合同：冷订阅、空 single/空流、分页结果容器、显式排序、count、聚合。后续任务必须复用这些基线，不能更新快照消除不兼容。

## 任务 2：建立完整事实合同，分离公共判断

**Files:**
- Modify: 文件组织中的三个 Schema 数据文件、三个 resolver；`QueryModelSchemaProvider.kt`
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSource.kt`、`JsonSchemaDeclarationWalker.kt`、`QuerySchemaDeclarationMerge.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/` 中现有能力及 metadata 类型
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaMergerTest.kt`（新增，复用当前声明构造器）及现有 `QueryModelSchemaTest.kt`、`QuerySchemaResolverTest.kt`、`DefaultQueryModelSchemaProviderTest.kt`
- Test: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSourceTest.kt`

**Interfaces:** 具体结构与查找合同见规约 6.1.1，properties/items/additionalProperties/alternatives 采用其唯一含义；保留 LogicalQuerySchema→Adapter→QueryModelSchema 两阶段；字段 lookup 与 binding 返回事实，不返回许可。增加 CURSOR_SORT；删除发布对象的 resolve(Query)。公共检查消费逻辑 Query 与 Schema，返回等价逻辑 Query 或明确错误，不产生物理 AST。

- [x] 先写反例：已知 `{int,string}` 与未知不可相等；Map 的数组值不继承容器 OBJECT；同级冲突失败、Unset 不覆盖、Set(null) 清除合法叶子、Mask 不因结构替换消失。
- [x] 锁定精确字段优先、元素相对路径和全部联合分支；指定全文字段缺能力必须失败，空字段显式搜索有正常对照。
- [x] 将公共判断从 Schema 方法迁到 resolver 所属公共函数，统一 Gateway 与 metadata 使用的静态规则；Provider 只合并和发布，不裁剪受保护字段的原生能力。
- [x] 写保护关系正反例：alias→secret 拒绝；请求父对象检查 secret 子节点；secret 的相邻 public 不受污染；不同来源命名空间的同字面路径不关联。保护索引只存事实，全部元素祖先保留。
- [x] 给 refresh 构造受控发布交错：旧请求保持旧实例，新请求取得新发布值；失败 refresh 保留旧完整快照；不逐请求构建字段关系索引。

```bash
./gradlew :wow-query:test --tests '*schema.*' :wow-schema:test --tests '*JsonQuerySchemaSourceTest' --console=plain
```

- [x] 输出字段合同与操作绑定的编译期类型，移除旧物理名称反向猜测。所有编译消费者列入任务 3 同次迁移；不得以全部拒绝让负例通过。

## 任务 3：两后端迁移与原生执行准入

**Files:**
- Modify: `QueryBackend.kt`、两后端 query 目录及现有 schema Adapter
- Test: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`、`EventStreamQueryBackendSpec.kt`
- Test: 两后端现有 `src/test/.../query/` 和 `src/integrationTest/.../query/` 测试

**Interfaces:** 六个目标方法如下；QueryBackendBinding 继续配对 Provider 与 Backend。

```kotlin
fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode>
fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode>
fun paged(query: IPagedQuery, schema: QueryModelSchema): Mono<PagedList<ObjectNode>>
fun cursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>>
fun count(query: FilterExpression, schema: QueryModelSchema): Mono<Long>
fun aggregate(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode>
```

- [x] 把原生 M1–M7/E1–E7 的输入移入调用真实 Wow Compiler/Backend 的测试：Mongo 混合族拒绝、数值同族正例、投影冲突与 `_id` 例外；ES keyword/source/runtime/nested 分别检验。
- [x] 在驱动查询、count 和 PIT 获取入口设置调用断言：静态拒绝时均为 0；paged 不能先 count 后发现 list 投影非法。
- [x] 删除 ResolvedQuery，六个 Backend 方法与所有真实调用方原子迁为 `(query, schema)`，无兼容重载。
- [x] 编译器从 Schema 的操作绑定生成物理请求；删除 resolvedField 依赖。只在后端解释 BSON/ES 类型。
- [x] 将 ES 静态聚合结构构造移至 PIT 获取前；统一检查 search/count/聚合的部分失败。保留资源管理原因链，Flux 已交付后错误不重放。
- [x] 迁移真实 TCK 与 backend fixtures，按每订阅独占 JSON、标准 JSON、取消/清理合同验证。

```bash
./gradlew :wow-mongo:check :wow-elasticsearch:check --console=plain
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --console=plain
```

预期：所有静态负例零执行性 I/O，正常输入返回真实预期结果。Docker 不可用只能报告集成未验收，不能用编译器字符串测试替代。

## 任务 4：固定 Gateway 管道，退出任意执行中间件

**Files:**
- Modify: `QueryGateway.kt`、`filter/QueryFilter.kt`、`filter/QueryContext.kt`
- Modify: `snapshot/filter/AbacQueryPolicy.kt`（职责迁为终端授权）、`FilterNormalizer.kt`
- Modify: `mask/SchemaMaskQueryFilter.kt`（退出注册）、`mask/SchemaMasker.kt`、`QueryLogObserver.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryScope.kt`：规约第 3 节的两个 Context 扩展，私有 key、缺省 MatchAll、重复写入 AND
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuard.kt`：同批退出旧 Filter SPI，保留校验/超时/输出行为在 HTTP 层的接入；不能只删旧守卫而形成可交付的无治理状态
- Test: `QueryGatewaySubscriptionTest.kt`、`QueryGatewayContractTest.kt`、模型 Gateway 与 Mask 测试

**Interfaces:** QueryContext<Q> 只读 query/namedAggregate/schema；QueryFilter.prepare 返回 Mono<Q>，仅保证原样/withFilter/appendFilter 与准备拒绝，不承诺 projection/sort/limit 的泛型重写。Backend 使用任务 3 的签名，Gateway 的十个方法不变。

```kotlin
interface QueryFilter {
    fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> =
        Mono.just(context.query)
}
```

- [x] 将 N1–N5/T1–T3 迁为真实 Gateway 测试，保留 N3 断言：准备阶段自身恢复不能吞终端错误。测试 `prepare = Mono.empty()` 时授权/执行入口不启动。
- [x] 顺序组合 prepare，捕获外层可信上下文；终端追加范围、ABAC、默认删除语义。public/native 两种检查分别承担各自工作，不重复授权。
- [x] 固定 Backend→Mask→物化→终止观察；对错误和取消只观察，不恢复执行。Schema 失败走无需 QueryContext 的外层错误入口。
- [x] 删除 `__QUERY__`、`__RESULT__`、next、一次性订阅标记、错误记忆槽和旧 Filter 链。动态输出不经 typed 往返转换。
- [x] 同时迁移 Snapshot/EventStream 构造与专属语义，以及 `wow-spring/src/main/kotlin/me/ahoo/wow/spring/query/SnapshotQueryGatewayRegistrar.kt`、`EventStreamQueryGatewayRegistrar.kt` 的 Filter 注册；`wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryAutoConfiguration.kt` 与其 `QueryAutoConfigurationTest.kt` 的 ABAC/Filter 注册同批更新，不留到任务 6 才修编译与装配。
- [x] 运行 query、Spring Registrar、starter 查询自动配置测试后才结束内部切换；CoSec 仅在实际源码存在消费时修改，不因为模块名推断依赖。

```bash
./gradlew :wow-query:check --console=plain
```

生产测试示例的必要断言模式（使用真实 Gateway fixture 替换局部模型）：

```kotlin
StepVerifier.create(gateway.dynamicList(query))
    .expectErrorMatches { it === terminalFailure }
    .verify()
verify(exactly = 0) { backend.list(any(), any()) } // 准备/公共拒绝场景
```

错误传播场景与零调用场景分别建 fixture，不能对同一次真实执行同时要求调用和零调用。

## 任务 5：HTTP 传输策略与元数据视图

**Files:**
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuard.kt`（退出 Filter，复用其校验）
- Modify: 同目录 `QuerySchemaHandlerFunction.kt`、`QueryRequestScope.kt`、`DefaultQueryRequestScope.kt`、`SingleQueryHandlerFunction.kt`、`ListQueryHandlerFunction.kt`、`PagedQueryHandlerFunction.kt`、`CursorQueryHandlerFunction.kt`、`CountQueryHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotAggregationHandlerFunction.kt`、`event/EventStreamAggregationHandlerFunction.kt`
- Consume: 任务 4 创建的 `QueryScope.kt` 与已退出旧 Filter SPI 的 HTTP 适配；不在本任务重复定义 Context helper
- Test: `HttpQueryGuardFilterTest.kt`、`QuerySchemaHandlerFunctionTest.kt`、Snapshot/EventStream 列表 Handler 测试

**Interfaces:** 输入为 Gateway 返回的原有 Mono/Flux；独立调用范围用规约第 3 节的 Context.withQueryScope / ContextView.queryScope 传递；原 RewriteRequestFilter 的范围提取结果单独传入，不继续合并用户 filter；metadata 通过任务 2 的公共静态规则派生。

- [x] 写 HTTP 反例：Schema 永不完成、每 2 秒一行持续 6 秒且 idle=3 秒、超限输出、timeout 关闭、SSE 中途错误。分别证明首次等待、闲置和缓冲位置。
- [x] 在最终元素流安装 timeout 和实际行数检查，再 collectList；超限错误并取消，禁止 take 截断成成功。分页/游标检查内部 list，不能使用 total 作为缓冲数量。
- [x] 用真实 Handler→Gateway 测试验证用户条件重写、prepare 内 contextWrite 均不能移除外层范围/替换授权身份；七种 Handler 入口共用 scope 接入，不只修改 list。
- [x] 保留输入配额与授权节点的区别；不把 HTTP 配额注入 JVM 默认行为。
- [x] metadata 仅展示有效逻辑能力；调用前后 Schema 原生绑定不变，受保护别名不能只在前端隐藏而后端仍允许。

```bash
./gradlew :wow-webflux:check --console=plain
```

## 任务 6：装配、消费者与旧路径退出

**Files:**
- Modify/Test: `wow-spring/.../query/` 的 Registrar 与 `QueryGatewayRegistrarTest.kt`
- Modify/Test: `wow-spring-boot-starter/.../query/` 的配置、UnavailableQueryBackend、自动配置测试
- Modify/Test: `wow-compiler` query 生成器、`wow-openapi`、`wow-apiclient`、`wow-cocache` 的受影响消费者
- Modify: `documentation/docs/zh/guide/` 下新增查询重构迁移说明；dashboard 只改生成输入后重新生成

**Interfaces:** 按聚合装配成对 Backend/Provider；不增加请求级切库或 fallback。Schema metadata 可破坏性迁移，查询 DTO 合同不变。

- [x] 对旧 validation-mode 配置增加显式迁移错误；工厂路由测试证明不匹配时按既有默认路由，执行失败不偷偷切库。
- [x] 逐个迁移编译器/Schema/OpenAPI/客户端消费者；DSL 仅生成逻辑 Query，嵌套字段按明确的相对路径合同调整。
- [x] 更新迁移文档，分别写 API 保留、内部 SPI 破坏、Schema wire 变更与行为变更；说明旧绝对/相对猜测及指定搜索字段 fallback 的新旧例子。
- [x] 搜索全部生产调用，删除旧字段/类型/保留属性；历史设计与迁移文档可以保留旧名，不作为残留代码。

```bash
rg -n 'ResolvedQuery|QueryRewriteMode|__QUERY__|__RESULT__|QuerySchemaValidationMode' --glob '*.kt'
./gradlew :wow-spring:check :wow-spring-boot-starter:check :wow-compiler:check :wow-openapi:check :wow-apiclient:check :wow-cocache:check --console=plain
```

## 任务 7：API、整体集成与性能关卡

**Files:**
- Test: 任务 1 的 API fixture、上述 TCK/集成测试
- Benchmark: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QueryGatewayBackendBenchmark.kt`、`QuerySchemaScaleBenchmark.kt`、`QuerySchemaResolverBenchmark.kt`
- Evidence: `build/query-architecture-migration/`（结果、命令、环境，不提交）

**Interfaces:** 不新增生产接口；产出可审阅的完成证据。

- [x] 用新产物运行任务 1 已编译的旧接口调用方，再重新编译 Kotlin/Java 样例；分别验证二进制调用与源码。JSON 样例保持，不靠批量更新基线通过。
- [x] 完成 A01–A14、R11–R15、N/T 对应的生产测试；旧中间件 P/Q 原型不计入最新实现覆盖率。
- [x] 运行相关模块 check 与两后端 integrationTest；失败保留原因，不声明生产准入。
- [x] 用现有 JMH packaging 任务生成基线/新实现可运行基准；基线必须从任务 1 的旧 HEAD 构建，不能用已修改实现冒充基线。

```bash
./gradlew :wow-benchmarks:tasks --all --console=plain
./gradlew :wow-benchmarks:jmhJar --console=plain
```

读取实际生成的 JMH jar 路径，用其 `-l` 列出 query 基准，再用相同参数分别执行旧/新 jar。保留 JVM、硬件、预热、测量、fork、数据和 JSON 结果；比较吞吐/分配/首项延迟，区分冷加载与热请求。不能把原型运行时间当性能数据；不能通过关闭授权、Mask 或完整性检查换取性能通过。

- [x] 对每项回归定位阶段及原因；无额外收益的索引、缓存或复制直接删除。确认无 Filter 时不创建插件监管状态，Schema 索引不逐请求重建。
- [x] 填写交付记录：改动、已运行命令、API 兼容结果、真实后端结果、性能结果、剩余限制。只有全部必要关卡通过才标记重构完成；提交、发布另按用户指令执行。

## 自审结论与实施方式

范围覆盖：整体设计 1–2→任务 1/6/7；3–4→任务 3/4；5→任务 4/5；6–9→任务 2/3/4；10→任务 1/6；11→任务 7。最新请求 SPI 在所有任务中一致；不复活 next 中间件。

采用当前工作区内互斥文件工作包并行执行，由协调者汇总审查和验证，先冻结基线，再进行同一合同下的内部切换。计划本身不代表生产实施已完成，也不包含自动提交或发布。

## 历史执行记录：兼容性基线阶段

本节及其后各工作包记录保留当时的状态和测试数量，便于追溯 RED→GREEN；不能将其中“尚未完成”或中间计数当作当前状态。当前覆盖关系与最新计数统一见[PR 前深度复核验收](2026-09-08-query-architecture-adversarial-review.md#pr-readiness)。

- 已冻结基线 HEAD `d82e5175dbd5ebe06ca3f63ac149f9ce0c6f62e7` 的 Java/Kotlin 调用方 class 与哈希；各调用全部十个方法。代理链接执行共 20 次调用成功，尚非目标 Backend 行为验证。
- 已记录并复核 11 份通用查询/结果 JSON；尚需补 Snapshot/EventStream 专属 typed 物化样例，不将通用样例宣称全模型覆盖。
- 原 16 项聚焦基线通过。Gateway API 测试与 Backend SPI 测试已分开；拆分后的 Gateway/Backend/订阅 14 项测试通过。原 Backend 约束仍保留，后续内部迁移只调整对应测试。
- JVM 描述符、测试 XML、命令日志和哈希见 `build/query-architecture-migration/baseline/`。冻结产物不能用新实现重新编译覆盖；新实现只运行旧 class 和比较 JSON。
- 生产实现尚未开始迁移，任务 1 的全模型行为基线仍未全部完成。此记录不代表任务 2–7 完成。

### 原生类型集合：首项生产迁移

- Snapshot/EventStream 物化输入和输出已冻结；模型 Gateway/公共合同 30 项通过。对冻结的两个模型 JSON 进行真实 Jackson 物化并比较输出，补齐前一记录的专属模型缺口。
- QueryFieldBinding.storageType 改为 storageTypes 集合；Mongo 保留原生联合集合，ES 绑定使用单元素集合。没有添加旧字段兼容桥，没有将集合转换成操作许可。
- 红阶段：已知 string/int 联合类型不应为未知的测试失败。绿阶段：两种顺序都精确保留同一集合；未知仍为 null。内部构造方、测试及 benchmark 消费者同步迁移。
- query/Mongo/ES 单元测试 395/288/222 项通过；真实 Mongo/ES 查询集成测试 87/95 项通过，无跳过。相关 detekt、Schema HTTP 聚焦测试、JMH 编译通过。原冻结调用方、DTO JSON 和 QueryGateway 描述符复核通过。
- 本项只修复绑定信息损失；CURSOR_SORT 准入、动态值完整结构、Schema.resolve 退出与新执行管道尚未实施。不得把本项通过称为整体重构完成或游标漏行修复。

证据位置：`build/query-architecture-migration/union-red.log`、`union-green.log`、`union-integration.log`、`union-detekt.log`、`union-consumers.log`、`union-verification.json`。所有产物保留本地，未提交或发布。

### 并行工作包 M1 / E1

- M1：生产修改集中于 MongoProjectionCompiler，原生投影映射后去重/去冗余，按路径段检查祖先；拒绝非法混用和 _id 子树冲突，保留 _id 双向开关例外。既有 paged 编译顺序正确，无需再在 Backend 重复校验；新增生产 Backend 测试证明非法投影时 count/find 零调用。
- E1：一个 ES 包内响应完整性 helper 被各响应入口复用，search 拒绝超时/失败分片，count 拒绝失败分片；请求禁止 partial，PIT 响应先记录最新 id 后检查，失败不作为成功末页。
- 两工作包均经过独立代码审查，无未解决发现。实现者验证：Mongo check 295 项、query 集成 87 项；ES check 228 项、query 集成 95 项，均无失败/跳过。最终综合验证另外记录，不混同实现者自报。
- 未修改 QueryGateway API、共享执行 SPI、依赖、模块、发布配置；PIT 前静态聚合构造及清理错误传播仍属后续生命周期工作包，Schema/Filter 整体切换仍未完成。

协调者综合验证：`:wow-query:check :wow-mongo:check :wow-elasticsearch:check` 成功；当前单元 XML 共 918 项、两后端查询集成 XML 共 182 项，均无失败/错误/跳过。冻结调用方哈希、实际代理调用、通用/模型 JSON、QueryGateway 描述符复核通过。证据：`build/query-architecture-migration/parallel-check.log`、`parallel-verification.json`。这些结果覆盖当前改动，不代表尚未实施的完整管道或性能基准通过。

### CURSOR_SORT 工作包（本包完成）

共享常量、公共 cursor 准入和有效 metadata 已改用 CURSOR_SORT；ES 字段名与物理重复检查下沉 native compiler。toMetadata 从 Schema 成员移为公共层扩展；Schema 保留按命名空间区分的来源事实索引，无 Mask 时不构建索引。其他聚合能力的 metadata/保护规则尚未一并迁移。

独立审查要求补两条必须通过的原生反例：Mongo 仅声明叶子而 validator 显示数组祖先时不得授予根游标；ES alias 指向数组来源不得绕过，且目标字段与 alias 不得作为两个不同物理排序字段。原生 alias 目标与 source 投影位置是不同事实，不能互换。两条反例均完成红绿修正及独立复审；本工作包已完成，完整架构迁移仍按后续任务推进。

CURSOR_SORT 最终证据：`:wow-api:check :wow-query:check :wow-mongo:check :wow-elasticsearch:check`、Schema HTTP 聚焦测试及 JMH 编译通过。API/query/Mongo/ES 单测分别 115/402/302/236，Schema HTTP 8 项，Mongo/ES 实库查询 87/95 项；均无失败、错误或跳过。冻结旧调用方、通用及模型 JSON、QueryGateway 描述符复核通过。命令与统计：`build/query-architecture-migration/cursor-final-check.log`、`cursor-final-verification.json`。

已完成的是原生游标能力/公共保护/有效 cursor metadata 的职责拆分；没有宣称数学极值全部证明、全量性能基准或新 Filter/Backend SPI 迁移完成。toMetadata 已移出 Schema 成员，但旧 Schema.resolve 与中间改写仍需退出；聚合保护规则仍需迁入共同的最终公共规则。

## 实施时的最终交付约束

用户最终要求为目标架构完整生产实现并验证先进性，验收按规约 11.3 执行；只保留 QueryGateway API 兼容，不考虑实现层兼容。当前原子工作包 B1 删除 ResolvedQuery：协调者负责 QueryBackend/Gateway/wow-query，Mongo/ES 子代理分别迁移自身模块，消费者子代理迁移 TCK、benchmark、Spring/starter。禁止替换成另一个公开同义包装或兼容重载。

B1 完成后仍必须继续 S1/S2/S3 的值结构与纯 Schema、G1/H1 固定运行时/HTTP、消费者和 V1 性能验收。计划最终状态只有在规约 11.3 全部有实现及证据时才可标为完成。


### 性能比较的运行条件

已保留原 HEAD 的可运行 JMH jar、源码提交及参数矩阵。最终比较必须在实现/测试工作停止后，将这个冻结 jar 与目标 jar 按同一环境、参数和测量窗口顺序复跑；开发期间并行构建取得的原始基线只作准备证据，不能直接与空闲机器上的目标结果形成提升百分比。保留时间误差、分配量与有效查询语义，单独记录实库端到端矩阵。

### S1/S2/S3 原子迁移中间检查点

已接入非泛型共享值树、独立 typed binding 索引、严格逻辑校验、原生时间归一化、递归 metadata、代内 Mask 数据。旧 schema.resolve、QueryRewriteMode、兼容等级和 validationMode 生产接口已移除。Schema 不执行查询或权限决策，Gateway API 保留。

第二轮 `:wow-query:test :wow-schema:test :wow-mongo:test :wow-elasticsearch:test --continue` 中，query/schema/Mongo 单测通过；ES 尚有两个 fixture 失败待修。脱敏源别名闭包仍处独立对抗审查，不能据此宣布整个目标完成。后续仍需外围模块 check、两个后端全量 integration、冻结 API 与同条件性能验证。证据为 `build/query-architecture-migration/schema-tests-2.log`。

## 整轮重构交付记录（历史）

目标架构已在生产路径整体实现，QueryGateway 的十个方法及 Query/结果 DTO 合同保留。内部 Schema、Backend、Filter、授权、HTTP 范围 SPI 已切换，旧模式和桥接路径已删除；无新依赖/模块，无版本或发布工作流变动。

最终完整相关模块检查及最后的 Mask 专项修订累计覆盖 1,904 项单测、Mongo 170 项和 ES 146 项完整集成测试，总计 2,220，无失败/错误/跳过。最后一次 Mask 修订的 query check、实库测试及 JMH 构建另见 mask-scalar-check-final.log、mask-scalar-final-check.log；后者测试成功、初次仅 lint 失败，随后修正 lint 并通过 check。不得把初次非零退出记成全项通过。

冻结旧调用方、七个描述符、通用/模型 JSON 再次复验；原始 Kotlin 命名参数和 Java 调用源码另编译到新目录并执行，没有覆盖旧 class。两个独立 Schema/Mask 探针在最终代码复验通过。

性能先修复不等价 fixture，再冻结配对，并对首轮异常做反序和较长预热。真实 Mask 字段补测暴露的逐值分配已经修正，受影响场景全部重测；保留 Schema 发布、小结果/扩展和缺席字段的成本，未用关闭检查换速度。首轮与最终 Mask 产物有独立 SHA 和 class 差异证明，未混称同一个构建。

逐项职责、测试和限制见[对抗报告第 16 节](2026-09-08-query-architecture-adversarial-review.md)，全部性能矩阵及可比性见[性能报告](2026-09-08-query-architecture-performance.md)。交付为当前工作区的代码与文档，不包含自动提交或发布。

## 全面 review 后的修复交付（历史）

上一轮验收后新增确认 F01–F13，用户授权修复。三个并行包分别处理 HTTP LOAD、Mongo、Elasticsearch，共享 Schema 和保护索引由协调者实现，各包独立复审，最后收口原生集成、API 和性能。该轮完成状态见[对抗报告第 17 节](2026-09-08-query-architecture-adversarial-review.md#_17-全面-review-的-13-项修复闭环)；此前 2,220 项计数仅属于上一轮代码。

本轮保留 QueryGateway API，不保留内部构造方法或实现 SPI 兼容桥。该轮相关单测 1,935、实库 323，共 2,258；原冻结调用方和源代码另编译验证通过。保护索引的专项配对单独记录，未重用旧的无 Mask 发布场景冒充有保护规则的构建性能。无依赖、模块、版本、CI/CD 变更，无提交或发布。

## 剩余债务清理

默认删除范围统一归 Gateway；原生 Backend/Compiler 遵循显式条件。旧 `Condition` 兼容栈保留至 10.0.0。数值合同与本轮完整验证以[PR 前深度复核验收](2026-09-08-query-architecture-adversarial-review.md#pr-readiness)为准，不能把上面的历史绿灯计作本轮新实现的验证。
