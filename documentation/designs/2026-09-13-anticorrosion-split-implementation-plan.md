# 收官防腐：聚合编译器/pager 同包机械拆分 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按收官决策的处方，对三个聚合热点文件做**零行为变化**的同包机械拆分：移除全仓库唯一的生产 `@Suppress("LargeClass")`，把单文件集中度降到可维护规模；同时将 Epic 收官决策中 Phase 5/6 修订为「明确不做」。

**Architecture:** 纯机械搬移——成员函数体逐字不动地移出类体，成为同包顶层函数/扩展（可见性 `private` → `internal`）；调用点语法不变（同包无需 import，成员扩展与顶层扩展调用语法相同）。无任何逻辑、签名（对外）、wire 变更。

**Tech Stack:** Kotlin（JVM 17）、detekt 1.23.8（LargeClass 阈值 600 行，未在 detekt.yml 覆盖；TooManyFunctions/LongParameterList/UnusedPrivateMember 已关闭）。

**Spec:** `documentation/designs/2026-09-12-aggregation-reporting-epic-design.md` 的「Phase 5-6 收官决策 → 后续维护注记 2」（拆分处方与安全网）；本计划的成员→文件映射表是搬运清单，行号基于 main `0da88a0c5`。

## Global Constraints

- **零行为变化**：函数体逐字搬移；唯一允许的文本变化 = (a) 从类体移出为同包顶层声明，(b) `private` → `internal`（仅当跨新文件引用时），(c) 文件头/导入整理，(d) `@Suppress` 注解随成员走（`group`/`project` 的 `LongMethod` 保留）。
- 对外（public/跨模块）API 不变；`internal` 成员保持 `internal`。
- 安全网（收官决策原文）：既有绿灯套件（模块 check + allLocalTest + allContractTest + 双后端 TCK integrationTest）+ **空 OpenAPI 快照 diff** + detekt 通过（Mongo 编译器不再需要 LargeClass 抑制）。
- 不新增测试（结构性单测断言的是管道/计划文档，行为不变则全绿即是锁）；不修改任何测试期望。
- fluent-assert、无 `@JvmOverloads`、Reactor 路径不动。
- 结构性测试文件（`MongoAggregationCompilerTest` 等）**不**拆分（超出处方范围）。

---

## 成员 → 文件映射（搬运清单）

### A. wow-mongo：`MongoAggregationCompiler.kt`（1027 行）→ 5 个文件

包：`me.ahoo.wow.mongo.query.aggregation`。唯一构造态 `filterCompiler` 留在编译器（仅 `compile`/`metricFilter` 使用）。

| 新文件 | 搬入成员（现文件行号） | 可见性变化 |
|---|---|---|
| `MongoAggregationCompiler.kt`（保留） | `compile`×2（61–122）、`group`（124–220，保留 `LongMethod`）、`metricFilter`（222–248）、`wrapParticipation`（250–257）、`wrapContribution`（259–266）、`DenseHistogramFill`（403–408）、`denseStages`（410–424）、`accumulate`（479–487）、`countAlias`（1025–1026） | 类成员不动（估 ~360 行）；对搬出成员的调用不需变化 |
| `MongoAggregationProjection.kt` | `project`（268–401，保留 `LongMethod`）、`denseRoundTripMatch`（426–459）、`denseKeyProjection`（461–468）、`denseDateAdd`（470–477）、`derivedProject`（489–511）、`toDerivedDocument`（513–554） | 全部 → internal 顶层 |
| `MongoAggregationGroups.kt` | `AggregationGroup.compile`（609–673）、`wallHourTruncation`（675–692）、`wallHourIndex`（694–719）、`denseHourKey`（721–747）、`dateInput`（875–894）、`mongoTimeZone`（896–903） | 全部 → internal 顶层（`mongoTimeZone` 被 4 处跨文件引用） |
| `MongoAggregationExpressions.kt` | `numericParticipation`（749–770）、`distinctCountInput`（772–782）、`toMongoExpression`（784–842）、`mongoOperator`（844–850）、`finiteDouble`（852–873）、`epochDate`（905–940）、`toEpochMillis`（942–950）、`floorDivide`（952–955）、`multiplyToLong`（957–960）、`numericInput`（962–982）、`scalarOrSingleton`（984–991）、`convert`（993–999）、`QueryField.resolve`（1001–1014）、`List<Sort>.toBson`（1016–1023） | 全部 → internal 顶层（`resolve` 是最广引用者，同包直用） |
| `MongoHavingDocuments.kt` | `toHavingDocument`（556–589）、`numericHavingMatch`（591–597）、`matchOperator`（599–607） | → internal 顶层 |

注：`countAlias` 经预检裁定 1 搬至 `MongoAggregationExpressions.kt`（成员扩展类外不可调用）。

### B. wow-elasticsearch：`ElasticsearchAggregationCompiler.kt`（750 行）→ 4 个文件

包：`me.ahoo.wow.elasticsearch.query.aggregation`。唯一构造态 `filterCompiler` 留在编译器（`compile`/`metricFilter`）。

| 新文件 | 搬入成员（现文件行号） | 可见性变化 |
|---|---|---|
| `ElasticsearchAggregationPlan.kt` | 顶层类型整体搬移：`ElasticsearchAggregationPlan`（53–64）、`DenseBucketPlan`（66–74）、`ElasticsearchAggregationElement`（76–79）、`ElasticsearchAggregationMetric` 及 6 个嵌套（81–123）、`valueCountAlias`（125–126） | 不变（已 internal） |
| `ElasticsearchAggregationCompiler.kt`（保留） | `compile`×2（131–188）、`compileMetrics`（190–221）、`toPlan`（383–441）、`metricFilter`（443–462）、`toDistinctCountPlan`（464–482）、`toPercentilePlan`（484–509） | 类成员不动（估 ~300 行） |
| `ElasticsearchAggregationSources.kt` | `toSource`（223–276）、`dateField`（278–298）、`epochDateRuntimeField`（300–348）、`missingKeyRuntimeField`（350–381）、`temporalSemantic`（729–738）、`epochFactors`（740–749） | → internal 顶层 |
| `ElasticsearchAggregationScripts.kt` | `toDerivedPlan`（511–524）、`toScript`（526–586）、`referencePaths`（588–621）、`RuntimeExpressionCompiler`（623–706，`private inner` → 同文件顶层 `private` class；其对外层 `QueryField.resolve` 的引用改经同包 internal 扩展）、`painlessOperator`（708–714）、`QueryField.resolve`（716–727，置于本文件供 Sources/编译器同包共用） | → internal 顶层（`RuntimeExpressionCompiler` 顶层 private） |

### C. wow-elasticsearch：`ElasticsearchAggregationPager.kt`（665 行）→ 4 个文件

构造态（client/indexName/batchSize/pointInTime）全部留在 pager 类。请求构建簇（209–330、633–660）仅 pager 调用 → 留守。

| 新文件 | 搬入成员（现文件行号） | 可见性变化 |
|---|---|---|
| `ElasticsearchAggregationPager.kt`（保留） | 常量（43–54）、pager 类（56–478，含嵌套 `AggregationPage`）、请求构建簇（209–330）与 `nestedAggregationName`/`filterAggregationName`/`putMetricAggregations`（633–660）留为类成员 | 不动（估 ~350 行） |
| `ElasticsearchAggregationResponses.kt` | `summary`（332–346）、`innermost`（348–360）、`innermostScope`（362–375）、`toRow`×2（377–388）、`value`（390–411）、`anyValue`（413–421）、`numericValue`（423–437）、`percentileValue`（439–449）、`nativeValue`（451–458）、`filtered`（662–665） | → internal 顶层 |
| `ElasticsearchAggregationHaving.kt` | `fillGapRows`（486–505，已 internal）、`matchesHaving`（512–524）、`isNullMetric`（526–529）、`metricDouble`（531–535）、`compare`（537–544） | `matchesHaving` → internal（pager 类跨文件调用）；其余文件内 private |
| `ElasticsearchAggregationRanking.kt` | `selectTopRows`（546–550，已 internal，仅测试引用）、`BoundedTopRows`（552–583）、`RankedRow`（585–588）、`rankedRowComparator`（590–601）、`compareValues`（603–616）、`toSortValue`（618–625）、`incomparableValues`（627–631） | `BoundedTopRows` → internal（pager 跨文件调用）；其余文件内 private |

跨文件耦合（已核实）：`metricFilterAggregationName`（pager:637，internal）被编译器 `referencePaths` 引用——两文件同包 internal，原样成立；`valueCountAlias` 移入 Plan 文件后被 pager 引用——同包 internal 原样成立。

---

### Task 1: 分支与收官决策修订

**Files:** Create 分支 `refactor/aggregation-compiler-split`（worktree）；Modify `documentation/designs/2026-09-12-aggregation-reporting-epic-design.md`；本计划入库为首提交。

- [x] 建 worktree 分支（main `0da88a0c5` 基）
- [x] Epic 文档收官决策修订：状态行与分期表 Phase 5「触发式推进」→「明确不做」、Phase 6「挂起」→「明确不做」；「Phase 5-6 收官决策」节首段改为最终定论（触发条件与重启条件表述改为“经维护者 2026-09-13 最终确认：明确不做”），维护注记 2 的前提句（“若重启 Phase 5/6…先做”）改为“收官防腐已随本决策执行（见 #<本 PR>）”；原提纲留档不动
- [x] 提交 `docs(design): finalize phase 5-6 as not-planned and schedule the anti-corrosion split`

### Task 2: Mongo 编译器拆分（A 表）

- [x] 按映射表搬移 4 个新文件；**删除** `@Suppress("LargeClass")`；搬移后逐一核对：函数体零改动、`LongMethod` 注解随 `group`/`project` 保留、`DenseHistogramFill`/`denseStages` 留守
- [x] 验证：`./gradlew :wow-mongo:test :wow-mongo:check`（含 detekt）全绿——结构测试即行为锁，期望零修改
- [x] 提交 `refactor(mongo): mechanically split the aggregation compiler by concern`

### Task 3: ES 编译器拆分（B 表）

- [x] 按映射表搬移 3 个新文件；`RuntimeExpressionCompiler` 由 `private inner` 改顶层 `private` class（对外层 `QueryField.resolve` 的引用改为同包 internal 扩展调用，函数体不动）
- [x] 验证：`./gradlew :wow-elasticsearch:test :wow-elasticsearch:check` 全绿
- [x] 提交 `refactor(elasticsearch): split aggregation plan types, sources and scripts`

### Task 4: ES pager 拆分（C 表）

- [x] 按映射表搬移 3 个新文件；`BoundedTopRows`/`matchesHaving` → internal，其余文件内 private；`selectTopRows`/`fillGapRows` 保持 internal（测试引用不变）
- [x] 验证：`./gradlew :wow-elasticsearch:test :wow-elasticsearch:check` 全绿（两个 pager 测试类不修改）
- [x] 提交 `refactor(elasticsearch): split aggregation pager responses, having and ranking`

### Task 5: 安全网 + PR

- [x] OpenAPI 快照空 diff：`./gradlew :wow-openapi:test -Dwow.snapshot.update=true` → `git status wow-openapi/` **必须无变化**
- [x] detekt 全仓 + `:wow-api:check :wow-query:check :wow-mongo:check :wow-elasticsearch:check :wow-webflux:check`
- [x] `./gradlew allLocalTest allContractTest --stacktrace`
- [x] `./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace`（双后端 TCK，Docker）
- [x] `./gradlew :wow-benchmarks:test :wow-benchmarks:benchmarkSmoke --stacktrace`
- [x] 确认全仓库无生产 `@Suppress("LargeClass")` 残留（grep）
- [ ] 推分支、开 PR（标题 `refactor(query): mechanically split aggregation compiler and pager hotspots`）

## Self-Review 记录

- 处方覆盖：收官注记 2 的三个热点文件全部拆分；LargeClass 抑制移除为显式验收项；安全网四件套（套件/快照空 diff/detekt/双后端 TCK）逐项列为 Task 5 步骤。
- 无占位符：搬运清单为逐成员行号映射（探查代理实测），机械规则四条穷尽文本变化类别。
- 类型一致性：跨文件耦合两处（`metricFilterAggregationName`、`QueryField.resolve`）同包 internal 化解，调用点语法不变。
