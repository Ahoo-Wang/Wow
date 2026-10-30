---
title: 经 CoCache 共享状态
description: 从共享的 CoCache 缓存读取其他上下文的聚合，按生成的视图读取，由拥有它的上下文维护，无需任何声明。
outline: deep
---

# 经 CoCache 共享状态

Saga、名称解析或内部计算常常要按 ID 多次读取另一个上下文的聚合。`wow-cocache` 把这些状态放在共享的 [CoCache](https://cocache.ahoo.me/) 缓存里：L1 是 Redis，每个服务各有本地 L2。

- **拥有**聚合的上下文在自己的提交之后，按版本顺序把每个状态写进缓存。
- 每个**读方**注入所生成视图的 `StateViewCache<V>` 直接读取。不用声明缓存，不用编写订阅，读方也不各存一份。

连不上共享 Redis 的服务，用[类型化的对端读取](../reading-state) `queries.snapshots<V>()` 读同样的视图：缓存只是这次读取的加速，答案不会不同。

## 添加模块

<!-- sample: fragment -->

```kotlin
dependencies {
    // 领域模块：缓存的接口，本身没有任何依赖。
    implementation("me.ahoo.wow:wow-cocache-api")
}
```

<!-- sample: fragment -->

```kotlin
dependencies {
    // 服务模块：经 CoCache 的缓存、CoCache 的 starter 与其 Redis 后端。
    implementation("me.ahoo.wow:wow-cocache")
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
    implementation("me.ahoo.cocache:cocache-redis")
}
```

`wow-cocache-api` 只有 `StateKey` 与 `StateViewCache`，领域模块用它们时既不会引入 CoCache，也不会引入 Redis。应用运行 CoCache 时，即 CoCache 的 starter 有后端时（`cocache.backend: redis`，配合 Spring 的 `spring.data.redis.*`；单个 JVM 用 `memory`），`wow-cocache` 自动启用。

## 读取状态

读方注入属主 api 模块生成的视图 `<State>View` 的缓存，按聚合的租户与 ID 读取：

<!-- sample: fragment -->

```kotlin
@Saga
class PaymentGatewaySaga(
    private val gateway: PaymentGateway,
    private val orders: StateViewCache<OrderStateView>,
) {
    @OnEvent
    suspend fun onPaymentRequested(event: StateTypedEvent<PaymentState, PaymentRequested>): CommandRequest<*> {
        val order = orders.get(event.ref.tenantId, event.ref.id)
        // ...
    }
}
```

缓存中没有、而属主此刻读不到（连不上、本节点没有到它的路由、超时），或缓存后端故障时，读取抛出 `StateViewUnavailableException`，并写出这些键。其他情况都不是它：解码不成视图的状态，或读取代码中的缺陷，都原样抛出。

是否在它上面回退由读方决定。为读方自己用途的读取（名称、描述）可以回退；决策所依据的读取不可以：它应当失败，处理函数会像任何失败一样重试，然后挂起。eShop 的支付读取它要扣款的订单，用来说明这笔扣款的内容，这是它自己的用途，所以支付从不为此等待订单服务。它只捕获不可用这一种情况（缓存在每次不可用期间只记录一次），并按订单 ID 扣款：

<!-- sample: examples/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentGatewaySaga.kt#read-order -->

```kotlin
/**
 * The order [orderId] as the ordering's shared cache gives it; null when the cache cannot serve it now (the
 * ordering does not share its orders here, or a miss cannot reach the ordering service). The charge's description
 * is the saga's own use of the order, so a payment never waits on the ordering for it. Only an unavailable order
 * falls back, which the cache itself logs: an order that does not decode, or a defect, fails the handling as any
 * error does.
 */
private suspend fun orderOf(tenantId: String, orderId: String): OrderStateView? = try {
    orders.get(tenantId, orderId)
} catch (_: StateViewUnavailableException) {
    null
}
```

- `get(key)` 与 `get(tenantId, id)` 返回视图；聚合不存在或已删除时返回 `null`。
- `getAll(keys)` 返回存在的聚合的视图：读一次缓存，未命中的按租户各向属主查询一次，ID 放在一个 `IN` 里。

缓存的一切都由视图推导：名字是 `wow.<context>.<aggregate>`，即视图的 `@StateView` 指明的聚合；键是聚合的租户与 ID；值是视图的 JSON。属主与每个读方推导出的完全相同，所以不需要任何配置让两侧对上。类路径上的契约指明的每个视图都有一个 `StateViewCache<V>` bean；Spring 之外用 `StateViewCaches.of<V>()` 得到同一个缓存。

缓存中没有的视图经节点的类型化查询向属主读取：节点自己承载该聚合时在进程内读取，否则以本服务身份经 HTTP 读取属主的内部端口（[调用其他服务](../calling-services)）。从未创建过的聚合会作为不存在缓存一段时间（CoCache 的负缓存）。

## 写入状态：属主

属主服务列出要共享状态的聚合类型：

```yaml
wow:
  cocache:
    publish:
      - eshop-ordering.order
```

每个列出的类型由一个独立的 sink 订阅 `cocache-sink.<aggregate>` 写入，它订阅属主的状态流，与任何订阅一样记录检查点、重试和挂起（[处理失败](../failures)）：

- 每次提交把提交后的状态按提交的版本写入，并且只覆盖更旧的版本。重复投递、重放，或新属主旁的旧属主，都不会让缓存倒退。
- 删除聚合的提交写入该版本的墓碑：读起来是不存在，任何更旧的状态都撤销不了它。
- 读方回源读取缺失的状态时持有该键在 CoCache 中的 lease，属主的写入会接管它：读到旧状态的回源永远写不回去。

没人读的状态不付写入成本：未列出的聚合类型不写入。列出的类型必须属于本服务，且其 api 契约中有视图；否则服务不会启动，错误会指出 `wow.cocache.publish`。

## 寿命与本地副本

- **状态在 Redis 中的寿命**：属主的 `wow.cocache.ttl`，作用于它写入的状态与墓碑。未设置时用缓存自身的寿命：CoCache 的 `cocache.caches.[wow.<context>.<aggregate>].ttl`，否则为其默认的一小时。
- **读方回源的寿命**：读方回源写入的状态按读方的 `cocache.caches.[wow.<context>.<aggregate>].ttl` 计。请让两侧寿命相同。新鲜度来自属主的写入，而不是寿命。
- **本地副本与负缓存**由各读方自己配置：`cocache.caches.[wow.<context>.<aggregate>].*`（`local-maximum-size`、`local-max-age`、`absent-ttl`），详见 CoCache 的文档。
- **不提前刷新**：这些缓存是带版本的，CoCache 的 `refresh-window` 不支持。为其中某个缓存设置它会导致启动失败。

```yaml
cocache:
  backend: redis
  caches:
    "[wow.eshop-ordering.order]":
      ttl: 30m
      local-max-age: 10s
```

## 受信读取

缓存里是本服务身份可见的完整视图，涵盖所有租户与所有者，只供受信代码读取：Saga、消息里的名称、内部计算。不得原样返回给终端用户：用户应读取属主的路由，那里会应用用户的范围。

缓存不是做决策的地方。命令只根据本聚合的状态决策；需要与另一个聚合保持一致的检查属于那个聚合，由它拒绝无法接受的命令（[拒绝](../rejections)）。

## 代价

- 属主与读方共用一个 Redis。缓存的名字、键和值（视图的 JSON）是服务之间的契约。视图的兼容性就是契约的兼容性：属主新增字段不会破坏任何读方，其余由兼容性闸门检查（[Manifest 与兼容性](../manifest)）。
- 每个共享的状态，属主每次提交多一次 Redis 写入。
- 缓存由 CoCache 自己的指标与健康检查报告（[可观测性](../../operate/observability)）。
