---
title: 自定义 Query Backend
description: 实现 QueryBackendFactory、稳定 descriptor、Plan V1、readiness 与 TCK 的完整边界。
---

# 自定义 Query Backend

`QueryGateway` 负责验证、schema、policy、budget 与 plan；Backend 只负责声明能力、检查 readiness、编译/执行 immutable Plan V1。不要在 Backend 中重新解析 legacy `Condition`，也不要从数据库 mapping 反推 logical schema。

## 绑定与执行

`QueryBackendFactory.bind(context)` 是同步、无 I/O 的单次绑定：

```kotlin
val factory = QueryBackendFactory { context ->
    require(context.schema.target == context.target)
    DocumentationBackend
}
```

每次 invocation 获得同一份 immutable `target + schema + securedExpression` 快照。网络、mapping、index 和 template 检查放在返回 Backend 的 `readiness()`；执行方法只消费 `SingleQueryPlanV1`、`ListQueryPlanV1`、`PageQueryPlanV1` 或 `CountQueryPlanV1`。

## Stable descriptor

下面的主体由 `QueryBackendDocumentationTest` 编译：

```kotlin
override val descriptor = QueryBackendDescriptor(
    backendId = "documentation",
    documentKinds = setOf(QueryDocumentKind.SNAPSHOT),
    planVersions = setOf(QueryPlanVersion.V1),
    portableOperators = PortableOperator.entries.toSet(),
    portableFeatures = QueryPortableFeature.entries.toSet(),
    stringComparisonModes = StringComparisonMode.entries.toSet(),
    capabilities = emptySet(),
    maxBudget = QueryBudgetLimit(maxResults = 1_000)
)
```

Descriptor 是能力承诺，不是运行时探测结果。缺少 operator、document kind、plan version、string mode 或 capability 时，Gateway 在执行前拒绝。FullText 和 Native 只在 descriptor、应用 enabled capability、Policy 决策及 schema binding 同时允许时执行；不能降级。

## Legacy → canonical operator matrix

| Legacy | Canonical |
|---|---|
| `AND` | `LogicalExpression(AND)` |
| `OR` | `LogicalExpression(OR)` |
| `NOR` | `LogicalExpression(NOR)`，不是一元 `NOT` |
| `ID` | document identity 字段上的 `EQ` |
| `IDS` | document identity 字段上的 `IN` |
| `AGGREGATE_ID` | `aggregateId EQ` |
| `AGGREGATE_IDS` | `aggregateId IN` |
| `TENANT_ID` | `tenantId EQ`，并提取 caller requested scope |
| `OWNER_ID` | `ownerId EQ`，并提取 caller requested scope |
| `SPACE_ID` | `spaceId EQ`，并提取 caller requested scope |
| `DELETED` | `RequestedQueryScope.deletion`；由 `SystemQueryPolicy` 唯一注入 |
| `ALL` | `MatchAll` |
| `EQ` | `PredicateExpression(EQ)` |
| `NE` | `PredicateExpression(NE)` |
| `GT` | `PredicateExpression(GT)` |
| `LT` | `PredicateExpression(LT)` |
| `GTE` | `PredicateExpression(GTE)` |
| `LTE` | `PredicateExpression(LTE)` |
| `CONTAINS` | `PredicateExpression(CONTAINS)` + string comparison mode |
| `IN` | `PredicateExpression(IN)` |
| `NOT_IN` | `PredicateExpression(NOT_IN)` |
| `BETWEEN` | `PredicateExpression(BETWEEN)`，恰好两个值 |
| `ALL_IN` | `PredicateExpression(ALL_IN)` |
| `STARTS_WITH` | `PredicateExpression(STARTS_WITH)` + string comparison mode |
| `ENDS_WITH` | `PredicateExpression(ENDS_WITH)` + string comparison mode |
| `MATCH` | `FullTextExpression(full-text)` |
| `ELEM_MATCH` | `ElementMatchExpression` |
| `NULL` | `PredicateExpression(NULL)` |
| `NOT_NULL` | `PredicateExpression(NOT_NULL)` |
| `TRUE` | `PredicateExpression(TRUE)` |
| `FALSE` | `PredicateExpression(FALSE)` |
| `EXISTS` | `PredicateExpression(EXISTS, BooleanValue)` |
| `RAW` | 预注册、typed `NativeExpression`；backendId 必须匹配 |
| `TODAY` | invocation frozen instant 上的相对时间范围 |
| `BEFORE_TODAY` | invocation frozen instant 之前的范围 |
| `TOMORROW` | invocation frozen instant 的次日范围 |
| `THIS_WEEK` | invocation zone 中的本周范围 |
| `NEXT_WEEK` | invocation zone 中的下周范围 |
| `LAST_WEEK` | invocation zone 中的上周范围 |
| `THIS_MONTH` | invocation zone 中的本月范围 |
| `LAST_MONTH` | invocation zone 中的上月范围 |
| `RECENT_DAYS` | frozen instant 向前 N 日范围 |
| `EARLIER_DAYS` | frozen instant 之前 N 日的上界 |

Canonical 还包含 `EMPTY_COLLECTION`，供 nullable collection 与 ABAC 使用；它没有旧 legacy operator。Backend 必须实现精确 empty-list 语义，不能把 missing 或 null 当 empty。

## Readiness

`readiness()` 只返回：

- `Ready`
- `NotReady(DEPENDENCY_UNAVAILABLE)`
- `NotReady(INDEX_MISSING)`
- `NotReady(MAPPING_INCOMPATIBLE)`
- `NotReady(CONFIGURATION_INVALID)`

NotReady 不应执行查询。诊断只能包含低信息 reason，不能泄露 mapping、index、tenant 或表达式内容。

MongoDB FullText readiness 要求目标 collection 与精确 text index；普通 portable 查询不应假装数据库能证明 schema-less 文档类型。Elasticsearch readiness 必须在同一 mapping/settings 快照上验证 system/application 类型、sort doc-values、analyzer、presence metadata 与 nested 语义。managed template 只影响新 index；既有不兼容 index 保持 NotReady，不自动 `putMapping`、删除或重建。

## TCK 与资源生命周期

Backend 实现必须运行共享 `SnapshotQueryBackendSpec` / `EventStreamQueryBackendSpec`，并使用 `QueryBackendTestKit`。至少证明：

- portable operator、projection、sort、page exact total 和 capability gate；
- subscription 前不执行、downstream demand、before/after-first error 与 cancellation；
- MongoDB cursor batch/killCursors，Elasticsearch PIT open/search/close；
- deadline、normal completion、backend failure、result-policy failure 五类终态恰好清理一次；
- readiness/schema/policy 失败时 data command 为零。

Elasticsearch list 使用 PIT 与 `search_after`，PIT ID 可能由每个 response 更新；终态必须关闭最新 ID。MongoDB 应设置有界 batch size；wire batch 是保守上界，不等同于可直接测量 JVM heap buffer。

## Mapping 与迁移

`QuerySchemaCustomizer` 只声明 logical schema 和显式 backend binding。Backend compiler 负责 physical path；不要把 Mongo `_id`、Elasticsearch multi-field 或 presence namespace 作为第二份业务 schema。

升级前验证实际 index/mapping。Elasticsearch 既有 index 不兼容时，创建新 index、回填并切 alias；MongoDB 使用显式 index reconciliation/迁移。框架第一阶段没有公开聚合分析 API，也没有运行时 `LEGACY`/`SHADOW` fallback。

参阅：[查询服务](../query.md)、[Filter 迁移](../migration/query-filter-to-query-policy.md)。
