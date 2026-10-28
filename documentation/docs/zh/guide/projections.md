---
title: 投影与反应器
description: 用投影从已提交的事件写出读模型，用反应器对它们采取行动，让两者都保持幂等，选择它们从哪里开始，并测试它们。
outline: deep
---

# 投影与反应器

投影和反应器都跟随一个聚合已提交的事件，并且都不返回任何东西。**投影**写出一个读模型，例如页面读取的一张表。**反应器**对外部世界采取行动：发送通知，或调用另一个系统。发送命令的处理器则是 Saga：见[编写 Saga](./sagas)。本页为 eShop 的订单各写一个。它们怎样收到事件，见[订阅、失败与重放](../concepts/subscriptions)。

## 你需要投影吗？ {#do-you-need-a-projection}

每个聚合已经保存了它状态的快照，而快照是可以查询的。当读模型需要与状态不同的形状（每个买家一行、跨聚合的汇总），或者另一个存储（检索索引、另一个系统读取的表）时，才写投影。

## 编写投影 {#write-a-projection}

投影是一个标注了 `@Projection` 的类。每个处理函数标注 `@OnEvent`，接收一个已提交的事件；它的依赖是构造函数的参数，从应用的 Bean 绑定：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSummaryProjection.kt#project -->

```kotlin
@Blocking
@OnEvent
fun onEvent(event: StateEvent<OrderState>) {
    val state = event.state
    summaries.save(
        OrderSummary(
            tenantId = event.ref.tenantId,
            orderId = event.ref.id,
            buyerId = event.scope.ownerId,
            status = state.status,
            totalAmount = state.totalAmount,
            units = state.quantities().values.sum(),
            updatedAt = event.commitTime,
        ),
        version = event.version,
    )
}
```

- `StateEvent<OrderState>` 对每个订单的每次提交调用一次处理函数，`state` 是提交之后订单的状态。改为接收 `StateTypedEvent<S, E>`，则只对 `E` 类型的事件调用，每个这样的事件调用一次。
- `scope.ownerId` 是提交之后聚合的所有者，这里是买家；`commitTime` 是它提交的时间。
- `@Blocking` 让处理函数在虚拟线程上运行，因为它要写入 Wow 之外的存储。处理函数也可以是 `suspend`，或者返回 Reactor 的 `Mono`。
- 这个处理函数是一个独立的订阅，名为 `OrderSummaryProjection.onEvent`。

## 保持幂等 {#keep-it-idempotent}

一次提交可能被处理不止一次：失败的尝试之后、重放时，或者节点之间交接租约时。所以投影的写入以它所写的版本为守卫，这样再次处理一次提交，或迟到地处理一次较旧的提交，都绝不会替换更新的行。订单汇总为每个订单保留最新的版本：

<!-- sample: examples/eshop/ordering/ordering-server/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingPorts.kt#save -->

```kotlin
override fun save(summary: OrderSummary, version: Int) {
    summaries.merge(summary.tenantId to summary.orderId, Versioned(version, summary)) { saved, newer ->
        if (newer.version > saved.version) newer else saved
    }
}
```

在 SQL 中，同样的守卫是一条 upsert，它只更新版本低于这次提交的行。

## 编写反应器 {#write-a-reactor}

反应器是一个标注了 `@Reactor` 的类。它的处理函数接收同样的事件，上下文为每次调用提供一个用于外部调用的幂等键：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/ShipmentNotifier.kt#notifier -->

```kotlin
/**
 * Tells the buyer of a shipped order that it is on its way. The notification service is a remote call that may be
 * slow or briefly down, so the reactor gives it its own budget: five attempts, from one second apart up to thirty, and
 * ten seconds for each, before the order is parked for the handler in the failure ledger. A buyer the service does not
 * know is a failure no retry can fix, so it parks the order at once.
 *
 * A reactor starts at the end of the log: deploying it does not notify the buyers of the orders shipped before.
 */
@Reactor
@Retry(
    maxAttempts = 5,
    initialBackoff = "1s",
    maxBackoff = "30s",
    timeout = "10s",
    unrecoverable = [UnknownBuyerException::class],
)
class ShipmentNotifier(private val notifications: BuyerNotifications) {
    @OnEvent
    suspend fun onOrderShipped(event: StateTypedEvent<OrderState, OrderShipped>, context: SubscriptionContext) {
        val buyer = event.scope.ownerId ?: return
        notifications.orderShipped(buyer, event.ref.id, key = context.invocationKey)
    }
}
```

同一个处理函数每次处理同一次提交时，`context.invocationKey` 都相同：处理整条流的处理函数是 `executionId`（`subscription:ref@version`），处理一种事件类型的处理函数是 `executionId:eventIndex`。把它作为幂等键传给外部系统，这样被要求两次的通知只会发送一次。`@Retry` 给反应器它自己的重试预算：见[失败与重试](./failures)。

## 从哪里开始 {#where-they-start}

在某个分区还没有检查点的订阅，按它的种类开始：

| 种类 | 从哪里开始 | 这样 |
| --- | --- | --- |
| 投影 | 日志的开头 | 部署它就会用历史填满它的读模型 |
| 反应器 | 日志的末尾 | 部署它不会就过去的事通知任何人 |
| 两者，读取另一个上下文的事件流时 | 主题的开头 | 之后才加入的服务仍能拿到之前发布的内容 |

`@OnEvent(start = ...)` 可以覆盖它，宿主的 `wow.subscriptions.[<processor.function>].start` 也可以，取值为 `beginning`、`end` 或一个 ISO-8601 时刻。两种方式都只在订阅还没有检查点时生效；之后它就从检查点继续。

## 重建读模型 {#rebuild-a-read-model}

因为投影的位置就是它的检查点，它的读模型总能从日志重建。要改变读模型的形状，就用新的名字部署新的投影：它从开头开始、追平，追平后把读取切到它的存储上。然后删除旧的那个。可选的做法见[订阅、失败与重放](../concepts/subscriptions#replay-and-backfill)。

## 测试 {#test-them}

`projectionSpec` 和 `reactorSpec` 把已提交的事件交给生成的处理函数，测试再检查假端口里有什么。把同一次提交交两次，就能说明投影是幂等的：

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSummaryProjectionTest.kt#test -->

```kotlin
@Test
fun `the same commit handled again should save the same summary`() = projectionSpec(handlers) {
    submitted()
    whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) { expectNoError() }
    whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) { expectNoError() }
    saved.distinct().size.assert().isEqualTo(1)
}
```

这些规格的说明见[测试领域代码](./testing#projections-and-reactors)。
