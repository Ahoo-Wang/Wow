---
title: "数据权限"
description: "通过租户、拥有者、空间与应用提供的 ABAC 过滤器约束 Wow 路由和查询，同时不把路由数据误当成授权。"
outline: deep
---

# 数据权限

Wow 在写链路和读链路中携带四类数据访问上下文：

1. **Tenant** — 聚合存储与路由作用域；
2. **Owner** — 可选拥有者元数据与路由作用域；
3. **Space** — 通过请求头提供的可选命名空间元数据；
4. **ABAC tags** — 资源标签与应用提供的 Principal 过滤器。

在 WebFlux 查询路由中，`RewriteRequestFilter` 会在生成的查询服务到达后端前追加 tenant、owner、space 元数据过滤器。已配置的 `AbacQueryFilter` 可以在同一 Handler 链中追加资源标签条件。

::: danger 作用域不是身份认证
tenant/owner 路径、`Wow-Space-Id` 请求头或 ABAC 标签都是路由与过滤数据，不能证明谁发送了请求，也不能证明该 Principal 有权选择这些值。应用必须先认证身份，在服务端绑定允许的作用域，授权命令和查询路由，并避免让不受信请求访问原始查询 Factory。
:::

## RESTful URL 模式

生成的聚合路由遵循以下形状，各可选段由聚合元数据决定是否启用：

```text
[tenant/{tenantId}/][owner/{ownerId}/]{resourceName}[/{resourceId}]/{action}
```

| 作用域 | 线协议值 | 路由条件 |
|---|---|---|
| Tenant | `tenant/{tenantId}` | 聚合没有静态 tenant ID |
| Owner | `owner/{ownerId}` | 有效 `AggregateRoute.Owner` 不是 `NEVER` |
| Space | `Wow-Space-Id` 请求头 | 有效路由启用 spaced |

`AGGREGATE_ID` 拥有者策略在 owner ID 已标识聚合时移除独立 resource-ID 段。命令级 `@CommandRoute` 可以覆盖聚合路由默认值。快照查询贡献者始终发布基础聚合路由，并在适用时增加 tenant/owner 变体，因此无作用域查询路由必须有显式安全策略。准确路由以运行服务的 OpenAPI 为准；限界上下文 alias 不会自动成为 URL 前缀。

查询 Schema 路由（`/{aggregate}/snapshot/schema` 与 `/refresh`）刻意不生成 tenant、owner 或 aggregate-ID 路径变体：它们描述聚合查询模型，而不是某个调用方的数据；spaced 聚合的公共聚合合同仍可能声明 `Wow-Space-Id`。

## 租户（Tenant）

Tenant 是 `AggregateId` 以及持久化消息/快照元数据的一部分。同一 named aggregate 内的 aggregate ID 仍需全局唯一；tenant 不会允许复用相同 aggregate ID。

### 基于注解的租户 ID

标记携带动态 tenant 标识的命令属性：

```kotlin
data class CreateOrder(
    @AggregateId val orderId: String,
    @TenantId val tenantId: String,
    val items: List<OrderItem>,
)
```

KSP 元数据记录 tenant 的解析方式。运行时 WebFlux 路由从路径读取 tenant，并将其带入命令或查询上下文。注解本身不会验证调用方是否属于该 tenant。

### 静态租户 ID

当一个聚合的所有实例都属于固定 tenant 时使用 `@StaticTenantId`：

```kotlin
@AggregateRoot
@StaticTenantId("system")
class SystemConfiguration(private val state: SystemConfigurationState)
```

生成路由不会包含动态 tenant 前缀。这只是路由/存储选择，并不表示资源公开。

### 默认租户

`TenantId.DEFAULT_TENANT_ID` 是 `(0)`。静态单租户聚合通常使用该值。当预期动态 tenant 时，不要把默认值作为授权回退。

## 拥有者（Owner）

Owner 是聚合内的快照和消息元数据，可以约束生成路由与快照查询；调用方身份仍必须由应用安全层绑定。

### 基于注解的拥有者 ID

标记提供拥有者元数据的命令字段：

```kotlin
data class CreateCart(
    @AggregateId val cartId: String,
    @OwnerId val userId: String,
)
```

不要仅因客户端字段带 `@OwnerId` 就信任其 `userId`。应在应用边界用已认证 Principal 比对或替换它。

### 拥有者路由策略

```kotlin
@AggregateRoot
@AggregateRoute(resourceName = "orders", owner = AggregateRoute.Owner.ALWAYS)
class Order(private val state: OrderState)
```

| 策略 | Owner 路径 | Resource ID | 含义 |
|---|---|---|---|
| `NEVER` | 无 | 正常保留 | 没有 owner 路由元数据 |
| `ALWAYS` | 必填 | 正常保留 | owner 与 aggregate ID 独立 |
| `AGGREGATE_ID` | 必填 | 移除 | owner ID 同时标识聚合 |

生成路由与 `OwnerAggregatePrecondition` 可以保证已加载聚合的 owner 元数据与路由值一致，但不能证明已认证调用方拥有该路由值。

### 拥有权转移

实现 `OwnerTransferred` 的事件会在溯源时修改状态聚合的 owner 元数据：

```kotlin
data class OrderAssigned(
    override val toOwnerId: String,
) : OwnerTransferred
```

领域模型决定何时允许转移。发出事件前应授权转移命令；标记接口只负责应用新元数据。

## 命名空间（Space）

Space 是随消息和快照存储的字符串命名空间，与 tenant、owner 相互独立，默认值为空字符串。

### 启用命名空间

```kotlin
@AggregateRoot
@AggregateRoute(resourceName = "sales-order", spaced = true)
class Order(private val state: OrderState)
```

WebFlux 读取 `Wow-Space-Id`，并在查询路由中追加 `SPACE_ID` 过滤器。请求头不会变成 URL 段，也不会认证调用方是否有权访问该 space。

### 命名空间转移

实现 `SpaceTransferred` 的事件会修改状态聚合的 space 元数据：

```kotlin
data class OrderArchived(
    override val toSpaceId: SpaceId,
) : SpaceTransferred
```

与拥有权转移相同，该事件只应用状态变更；是否允许变更由命令侧策略决定。

## ABAC（基于属性的访问控制）

Wow 保存资源标签，并以 `AbacQueryFilter` 提供扩展点。应用从已认证上下文提供 Principal 标签，并决定缺少上下文时公开还是拒绝。

### 核心概念

`AbacTags` 是 `Map<String, List<String>>`：

```kotlin
val principalTags = mapOf(
    "department" to listOf("engineering", "product"),
    "role" to listOf("reader"),
)

val resourceTags = mapOf(
    "department" to listOf("engineering"),
)
```

对于每个 Principal 标签 key，普通值会匹配“资源缺少该 key / 值为空 / 与 Principal 值有交集”中的任一情况。`listOf("*")` 会映射为该 key 的 `EXISTS` 条件。多个 Principal key 之间用 `AND` 组合。

因此，在内置标签匹配表达式中，无标签资源是公开的。如果这不是业务规则，应覆盖策略，而不是把标签机制当成完整授权系统。

### 应用资源标签

`DefaultApplyResourceTags` 是内置命令，使用 `PUT`、action `tags` 和 ID 路径。当聚合没有注册自己的 `ApplyResourceTags` 处理函数时，Wow 可以使用默认命令函数，并把 `DefaultResourceTagsApplied` 溯源到状态聚合元数据。

```kotlin
val command = DefaultApplyResourceTags(
    tags = mapOf("department" to listOf("engineering")),
)
```

该命令与其他改变授权状态的操作一样必须受到保护。发布生成路由不等于该端点只允许管理员调用。

### 标签合并

`merge` 合并同一 key 的值：

```kotlin
val effective = mapOf("department" to listOf("engineering"))
    .merge(mapOf("department" to listOf("product"), "role" to listOf("reader")))
```

应明确选择“替换”还是“合并”。`DefaultResourceTagsApplied` 会替换状态聚合中保存的资源标签，不会自动合并。

### 动态标签提取（StateAggregateTagsExtractor）

需要从物化聚合派生标签时，在状态上实现 `StateAggregateTagsExtractor<S>`：

```kotlin
class OrderState(
    val department: String,
) : StateAggregateTagsExtractor<OrderState> {
    override fun extract(source: ReadOnlyStateAggregate<OrderState>): AbacTags =
        mapOf("department" to listOf(department)).merge(source.tags)
}
```

Extractor 在状态物化期间计算资源元数据，不负责解析 Principal 身份。

### ABAC 查询过滤器

继承 `AbacQueryFilter`，并让受保护查询失败关闭：

```kotlin
@Component
class MemberAbacQueryFilter(
    private val memberships: MembershipRepository,
) : AbacQueryFilter() {
    override fun getPrincipalTags(
        contextView: ContextView,
        context: QueryContext<*, *>,
    ): Mono<AbacTags> = contextView.getOrEmpty<Principal>(Principal::class.java)
        .map { principal -> memberships.tags(principal.name, context) }
        .orElseGet { Mono.error(AccessDeniedException("Missing principal")) }
}
```

该示例代表应用策略，应根据实际安全上下文调整。框架对空 tags 或 `Mono.empty()` 的默认结果是 `MatchAllFilter`，所以受保护应用必须显式拒绝缺失身份或标签。

### 查询入口与策略执行

Spring 注册的聚合 `SnapshotQueryService` 与 `EventStreamQueryService` 代理通过 `QueryGateway` 执行。请求作用域重写、已配置 ABAC 过滤器与结果脱敏都在该链中应用。

`SnapshotQueryServiceFactory` 与 `EventStreamQueryServiceFactory` 是原始后端入口。直接创建的服务会绕过生成的代理 / `QueryGateway` 策略链；注册在生成服务名下的自定义 Bean 也会按原样使用，不会再包装。两者都应视为受信基础设施访问。

聚合查询会复用快照过滤器链处理根 filter，但结果脱敏会跳过动态聚合行。不能仅因普通快照存在脱敏就开放聚合接口。

## 必须完成的安全闭环

1. 请求进入生成路由前完成身份认证。
2. 从受信的服务端成员关系数据推导允许的 tenant、owner、space。
3. 授权命令，特别是 owner/space 转移和资源标签变更。
4. 为受保护数据注册失败关闭的查询策略。
5. 让原始 Factory 和未包装自定义服务远离不受信请求路径。
6. 测试匿名、伪造作用域、跨租户、缺少标签、聚合以及原始入口等负例。

## 隔离层级总结

| 层级 | 存储/查询元数据 | HTTP 表达 | Wow 提供 | 应用必须提供 |
|---|---|---|---|---|
| Tenant | `tenantId` | 动态时使用路径前缀 | 传播与查询约束 | 已认证作用域绑定 |
| Owner | `ownerId` | 可选路径前缀 | 路由形状、前置检查、查询约束 | 调用方到 owner 的授权 |
| Space | `spaceId` | `Wow-Space-Id` 请求头 | 传播与查询约束 | 调用方到 space 的授权 |
| ABAC | `tags` | 内部查询过滤器 | 标签存储与扩展点 | Principal 解析和失败关闭策略 |

只有应用完成身份绑定与授权后，这些机制才形成安全边界。路由形状、元数据传播、查询 Schema 校验与 HTTP 成本护栏都不能替代该策略。
