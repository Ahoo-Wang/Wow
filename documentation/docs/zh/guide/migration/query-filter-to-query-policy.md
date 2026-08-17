---
title: Query Filter 迁移到 Query Policy
description: 把旧 QueryFilter 扩展迁移到 QueryGateway 的 request、QueryPolicy、ResultPolicy 与 Backend 边界。
---

# Query Filter 迁移到 Query Policy

8.x 查询入口已经统一到 `QueryGateway`。旧 `QueryFilter`、`QueryHandler` 与 `QueryContext` 类型已删除，不能再作为运行时扩展点。迁移时先判断责任属于哪一层，不要把所有逻辑重新包装成“条件贡献器”。框架不存在 `QueryConditionContributor` 或替代 Filter hook。

## 责任决策表

| 需求 | 新位置 | 原因 |
|---|---|---|
| 只影响一次查询 | request / Query DSL | 调用方明确拥有该条件 |
| 调用方可覆盖的领域默认值 | 领域 Query Facade / request builder | 默认值仍属于调用方输入 |
| 所有入口都必须执行且调用方不可删除 | `QueryPolicy` | Gateway 在 backend I/O 前合并 mandatory expression |
| 返回值脱敏 | `ResultPolicy`；旧 masker API 仅作兼容适配 | 每个结果只执行一次，流式失败有统一 partial-result 语义 |
| logical field、physical field、能力绑定 | `QuerySchemaCustomizer` + Backend compiler | Policy 不知道数据库字段名 |
| MongoDB / Elasticsearch 执行 | `QueryBackendFactory`、`QueryBackend`、Plan V1 consumer | Backend 只消费 immutable schema 与 secured plan |

`RewriteRequestCondition` 仅用于 8.x HTTP wire 兼容。它只能产生 append-only `LEGACY_ENRICHMENT`，已 deprecated，也不是授权来源。route、path、header 和 request body 都是 `CALLER_REQUEST`；只有认证适配器提供的 authority 才能成为 trusted authority。

## Before：旧 Filter（只用于识别待迁移代码）

```kotlin
// 旧代码：类型已经删除，不能复制到新应用。
class TenantSnapshotQueryFilter : SnapshotQueryFilter {
    override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
        context.asRewritableQuery().rewriteQuery { query ->
            query.appendCondition(Condition.tenantId(currentTenantId()))
        }
        return next.filter(context)
    }
}
```

## After：mandatory tenant policy

下面的主体逻辑由 `QueryPolicyDocumentationTest` 编译并执行：

```kotlin
object TenantPolicy : QueryPolicy {
    override fun evaluate(context: QueryPolicyContext): Mono<QueryPolicyResult> {
        val tenantId = context.invocationScope.trustedAuthority.tenantId
            ?: return Mono.error(QueryPolicyDeniedException("TENANT_REQUIRED"))
        return Mono.just(
            QueryPolicyResult(
                mandatoryExpression = PredicateExpression(
                    LogicalField("tenantId"),
                    PortableOperator.EQ,
                    listOf(QueryValue.StringValue(tenantId))
                ),
                constraints = QueryPolicyConstraints(
                    fieldAccess = QueryFieldAccess.Restricted(
                        setOf(LogicalField("tenantId"), LogicalField("state.status"))
                    ),
                    capabilityAccess = mapOf(QueryCapabilityId("full-text") to CapabilityDecision.GRANT),
                    maxBudget = QueryBudgetLimit(
                        timeout = Duration.ofSeconds(2),
                        maxResults = 100,
                        maxCost = 1_000
                    )
                )
            )
        )
    }
}
```

`mandatoryExpression` 追加到调用方表达式，不能覆盖或删除。`fieldAccess` 限制查询、排序和结果字段；`capabilityAccess` 对 FullText/Native 等能力做 `GRANT`、`DENY` 或 `ABSTAIN` 决策；`maxBudget` 与 request、system、backend 上限取最小值。

### Spring 注册顺序

把 Policy 声明成普通 Spring bean；需要确定顺序时使用 `@Order`。顺序只决定观察和合并次序，不能让后注册 Policy 覆盖前一个 mandatory 条件或扩大字段/预算。

```kotlin
@Bean
@Order(100)
fun tenantQueryPolicy(): QueryPolicy = TenantPolicy
```

### Non-Spring

非 Spring 应用显式创建唯一 Gateway，并把 Policy 放进同一个 configuration；不要为每个 repository 创建 Gateway：

```kotlin
val gateway = QueryGatewayFactory.create(
    QueryGatewayConfiguration(
        admission = admission,
        schemaResolver = schemaResolver,
        backendResolver = backendResolver,
        customPolicies = listOf(TenantPolicy),
        resultPolicies = listOf(maskingResultPolicy),
        clock = clock,
        zoneId = zoneId,
        structureLimits = structureLimits,
        systemBudgetLimit = systemBudgetLimit,
        enabledCapabilities = enabledCapabilities,
        meterRegistry = meterRegistry
    )
)
```

### Policy 测试

使用发布的 `QueryPolicyTestKit` 验证 mandatory 表达式与拒绝原因：

```kotlin
QueryPolicyTestKit(TenantPolicy, contextWithTrustedTenant)
    .expectMandatory(expectedTenantPredicate)

QueryPolicyTestKit(TenantPolicy, contextWithoutTenant)
    .expectDenied("TENANT_REQUIRED")
```

## 安全迁移清单

- tenant/owner/space 与 trusted authority 不一致时，必须在 resolver、readiness、backend execution 前拒绝。
- mandatory authority 缺失时 fail closed；不要回退到 header、path 或 caller predicate。
- capability 请求只有至少一个 `GRANT` 且没有 `DENY` 才允许；全 `ABSTAIN` 必须拒绝。
- Policy 返回 empty、异常、非法字段或非法 capability 时 fail closed，不继续 backend I/O。
- 客户端伪造的 `Wow-*`、`CoSec-*` 或普通 header 不能升级成 trusted authority。
- legacy QueryService、直接 Gateway 与 WebFlux 必须命中同一 Policy/ResultPolicy 链。
- 脱敏只放在 `ResultPolicy`；不要同时保留旧 Filter 或在 facade 后置二次脱敏。
- FullText 与 Native 必须由 schema + policy + backend 三方同时支持；禁止降级为普通字符串或 RAW 查询。

## 行为修正

升级后以下旧行为不再保留：

- NoOp 查询不再返回空列表或 `0`，而是稳定 `BACKEND_NOT_READY / BACKEND_RESOLUTION / BACKEND_UNAVAILABLE`。
- 非法 page、limit、projection、sort 与 unknown field 在 backend I/O 前失败。
- Elasticsearch list 不再隐式截断到 10,000；page 返回 exact total。
- list 在首项后失败返回 `INCOMPLETE_RESULT`；JSON array 不写关闭 `]`，SSE 写一个安全终结 error event。
- 不兼容或缺失的 MongoDB index / Elasticsearch mapping 返回 NotReady，不自动修改既有资源。

## 升级与回滚

升级前先用目标版本检查 MongoDB index、Elasticsearch template/mapping 与 presence metadata。既有 Elasticsearch index 不兼容时，创建新 index、回填数据并通过 alias 原子切换；不要让应用启动时修改旧 mapping。

回滚是部署上一制品版本并按已演练的 alias/route 恢复；运行时没有 `LEGACY`、`SHADOW` 或双引擎开关。若新写入的 presence metadata 或索引结构与旧制品不兼容，必须在发布前写明数据与 alias 回滚步骤。

继续阅读：[查询服务](../query.md)、[自定义 Query Backend](../extensions/query-backend.md)、[数据权限](../data-access.md)。
