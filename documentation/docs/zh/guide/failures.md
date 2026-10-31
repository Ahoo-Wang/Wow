---
title: 失败与重试
description: 处理函数失败时会发生什么，怎样给它自己的重试预算和超时，失败怎样分类，命令在每一层怎样重试，以及停放的聚合怎样重试或跳过。
outline: deep
---

# 失败与重试

Saga、投影或反应器有时会失败：邮件服务不可用、某一行违反了约束、一个缺陷遇到了没人预料到的值。Wow 既不跳过这个事件，也不停止订阅。它重试处理函数；重试也无济于事时，就停放处理函数失败的那一个聚合，而订阅照常处理其他每个聚合。本页说明怎样为一个处理函数调整这些行为。模型见[订阅、失败与重放](../concepts/subscriptions#failures-park-one-aggregate)。

## 处理函数抛出异常时会发生什么 {#what-happens-when-a-handler-throws}

1. **重试。** 在重试预算之内再次调用处理函数：一共 5 次，第一次失败后等待 100 毫秒，之后每次等待前一次的两倍（即 `multiplier`，2），最长 5 秒。除非处理函数设了 `timeout`，一次尝试运行多久都可以。
2. **停放。** 预算用尽时，订阅在失败台账里记下订阅、聚合、它停下时的版本和错误。这个聚合后续的事件等待；其他每个聚合照常推进。
3. **稍后重试。** 停放的聚合在更长的周期内重试：10 次，第一次在停放 3 分钟后，之后每次在前一次开始后等待前一次的两倍，总共约两天。每次重试都以完整的预算再次运行处理函数。重试无法改变的失败，即不可恢复的失败，不这样重试。
4. **重试或跳过。** 这些重试也用尽后，聚合等待运维人员处理：重试它，即从它停下的版本开始重新读取它的流；或者跳过它，即越过它扣下的那些事件，不再处理它们。

Saga 派生的命令被拒绝不是失败：它从不重试，而是交给 Saga 的 `@OnRejected` 处理函数（[编写 Saga](./sagas#handle-a-rejection)）。Saga 的处理函数从 `context.call` 放过的 `CommandRejectedException` 也一样：交给它所调用命令的 `@OnRejected` 处理函数。没有这样的处理函数，或者异常来自处理函数使用的 `CommandClient` 时，立即停放这个聚合，不做重试。在投影或反应器中，`CommandRejectedException` 与其他失败一样：先重试，再停放。

## 给处理函数它自己的预算 {#give-a-handler-its-own-budget}

调用缓慢或不稳定的系统的处理函数，需要它自己的预算。写在处理器类上的 `@Retry` 适用于它所有的处理函数；写在处理函数上的，则逐项覆盖处理器的设置：

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

| 设置 | 含义 | 默认值 |
| --- | --- | --- |
| `maxAttempts` | 总调用次数，至少为 1 | 5 |
| `initialBackoff` | 第一次失败后的等待 | 100 ms |
| `multiplier` | 之后每次等待是前一次的多少倍，至少为 1 | 2 |
| `maxBackoff` | 两次尝试之间最长的等待 | 5 s |
| `timeout` | 一次尝试能运行多久：超时的尝试会被取消，并计为失败 | 无：一次尝试运行多久都可以 |
| `unrecoverable` | 立即停放聚合、不重试的异常类 | |
| `recoverable` | 在预算内重试的异常类，即使订阅本会把它们当作故障 | |
| `systemic` | 属于故障、而不是聚合自己的异常类：处理函数调用的下游不可用。它们会暂停分区，见[下文](#how-a-failure-is-classified) | |

时长写成 Kotlin 时长（`500ms`、`2s`、`1m 30s`）或 ISO-8601（`PT2S`）。没写的设置取自处理器的 `@Retry`，再没有则取自宿主的 `wow.subscription.retry.*`，表中给出的就是它的默认值。处理函数自己的异常类列表会替换处理器的。

`@Blocking` 的处理函数在虚拟线程上运行，超时无法中断它：它的尝试在调用返回时才计为失败。

宿主可以按订阅逐项覆盖注解，不需要重新构建。订阅的键是它所在上下文的名称加上它自己的名称，`<context>.<processor.function>`：

```yaml
wow:
  subscription:
    retry:
      max-attempts: 5
  subscriptions:
    "[eshop-ordering.ShipmentNotifier.onOrderShipped]":
      retry:
        max-attempts: 8
        multiplier: 3
        timeout: 20s
```

键所指的订阅不在这个节点上时，节点在启动时停止，所以拼错的键不会被忽略。宿主不对任何异常分类：这些列表属于处理函数。任务的订阅改在它的任务下配置，即 `wow.schedules.<job>.retry.*`（[定时命令](./scheduling#run-a-recurring-job)）。

## 失败怎样分类 {#how-a-failure-is-classified}

失败并不都是聚合自己的，所以每次失败在计数之前都会先分类：

| 类别 | 例子 | 会发生什么 |
| --- | --- | --- |
| 不可恢复 | 处理函数 `unrecoverable` 中的类；处理函数抛出的 `UnrecoverableHandlingException`；存储或 broker 永久拒绝的值（例如过大的记录） | 立即停放聚合，它的停放重试会跳过它：只有运维人员会重试它 |
| 可恢复 | 大多数异常 | 在预算内重试，然后停放聚合 |
| 系统性 | 处理函数 `systemic` 中的类；存储、broker 或连接不可用；broker 拒绝节点的凭据；一个分区的 3 个聚合在 10 秒内都失败、其间没有成功（`wow.subscription.systemic.*`） | 整个分区暂停：每次只用一次尝试去探测，间隔从 1 s 到 30 s，直到有一次处理通过。其间没有聚合会用掉预算或被停放 |
| 背压 | 因繁忙被拒的命令（`wow.command.busy`）、超时的等待、查询存储的舱壁、带 `Retry-After` 的 429 | 这个条目以 100 ms 到 10 s 的间隔重试，加抖动，并且至少等待 `Retry-After`，不消耗处理函数的尝试次数。每次被拒都使分区的并发减半，每次成功再使它回升。一个条目被拒 20 次（`wow.subscription.backpressure.max-attempts`）后停放聚合 |

处理函数自己的 `recoverable`、`unrecoverable` 和 `systemic` 列表优先，它们匹配的是处理函数抛出的那个异常。一个类出现在多个列表中时，在它的类层次中更近的那个说了算；距离相同时，依次是 `recoverable`、`systemic`。之后由分类器依次读取这个异常和它的每个起因：应用的 `FailureClassifier` Bean，然后是各模块的（PostgreSQL 模块对它的驱动，客户端对网络），最后是 Wow 自己的规则，它认识自己的存储、broker 和查询。应用的分类器对它认识的失败返回一个类别，其余返回 null，例如处理函数直接调用的驱动或客户端的异常。没有被任何东西分类的失败是可恢复的。

促成暂停的那些失败已经用掉了它们的尝试；分区暂停之后，任何失败都不再消耗尝试。暂停不是重试预算：故障持续多久，分区就暂停多久。

上面的处理函数把 `UnknownBuyerException` 标为不可恢复：通知服务不认识这个买家，再问一次也不会改变，所以立即停放这个订单，等人来查看。

## 停放的聚合 {#parked-aggregates}

失败台账放在服务自己的存储里，与检查点在一起。长周期的重试由 `wow.subscription.parked.*` 设置，或者按订阅由 `wow.subscriptions.[<context>.<processor.function>].parked.*` 设置：

| 设置 | 含义 | 默认值 |
| --- | --- | --- |
| `max-retries` | 停放的聚合的自动重试次数；0 关闭自动重试 | 10 |
| `initial-backoff` | 第一次重试前的等待；之后每次在前一次开始后等待前一次的两倍 | 3 min |
| `claim-ttl` | 一次重试占住条目多久，之后另一个节点（例如分区的下一个属主）才能重试它。它不会取消任何东西 | 2 min |

不可恢复的失败会被停放，但不会自动重试：任何重试都改变不了它。

运维人员在 ops 控制台里看到每个服务停放的聚合，以及每个聚合的错误和停下时的版本，并在那里重试或跳过它们：见 [ops 服务与控制台](../operate/ops-console)。聚合停放或用尽重试时，webhook 可以通知团队。

停放的定时器也列在那里，在订阅 `wow.timers.sends` 之下，每个目标聚合一个条目。它们不会自动重试。重试这个条目会以新的尝试次数再次发送它的定时器；跳过它会以结果 `SKIPPED` 取消它们。

重试之前先修复原因：部署修正后的处理函数，或者让外部系统恢复。重试的聚合会从它停下的版本开始重新读取它的流，所以它的处理函数会按顺序看到它错过的每个事件；运维人员的重试不消耗它的自动重试次数。跳过把条目标为已解决：它扣下的事件永远不会被处理，这个聚合之后的事件会照常处理。

## 命令怎样重试 {#how-a-command-is-retried}

本页前面讲的是处理函数。命令同样在几层上重试，而且每一层都保持它的请求 ID：重试是同一个请求，所以目标只决定它一次。

**在内核中**，在应答调用方之前：

- **请求 ID。** 一条命令的请求 ID 如果已经在它的聚合类型、同一租户中提交过，就不会再次决定，而是用那次提交来应答：`duplicate: true` 和它的版本，但没有第一次应答的结果。请求 ID 被另一种命令类型提交过时，命令会被拒绝，`wow.command.request-id-reused`（409）。没有带请求 ID 的命令使用它的命令 ID。
- **版本冲突。** 另一个写入者先追加了：这次尝试被补偿，命令基于更新后的状态重新决定，总共最多 3 次，这个上限没有设置可以改变。之后以 `wow.command.contended`（409，`Retry-After: 1`）失败，可以用同一个请求 ID 再次发送。与同一个请求的追加冲突时，则是这个请求的重复请求。
- **缓存的状态。** 基于缓存状态被拒绝、或者什么也没改变的命令，会与日志核对。日志已经越过缓存时，命令基于更新后的状态重新决定，不计入尝试次数。
- **未知的结果。** 追加的应答丢失时，内核读取这次追加本应占用的版本：是自己的提交即为成功，是同一请求的另一次提交即为重复，是另一个请求的提交即为冲突。仍然无法判断时，回答 `wow.command.unknown`（503，`Retry-After: 1`）：命令可能已经提交。转发给分区属主、在 `wow.cluster.forward-timeout` 内没有得到回复的命令也这样回答，并且不在它到达的节点上执行，否则它可能被决定两次。确定属主从未收到的命令，则在它到达的节点上执行。

**按来源**，在内核应答之后：

| 来源 | 谁来重试 | 怎样重试 |
| --- | --- | --- |
| HTTP 调用方 | 调用方 | 服务端从不重试请求。409 `wow.command.contended`、503 或 429 要用同一个 `Wow-Request-Id` 再次发送（[安全地重试](./http#retry-safely)）。Wow 的 Kotlin 客户端会自己这样做：共 5 次，间隔从 200 ms 起倍增，`Retry-After` 不超过 5 s 时按它等待（`wow.peer.retry.*`）；要求更久时不再等待，调用失败。连不上某个节点，或节点回答它正在关闭时，换到对端的另一个节点，不计入尝试次数。 |
| Saga | 它的订阅 | 派生命令的请求 ID 来自提交、它的目标、它的命令类型和它在其中的序号，或者请求自己的 `requestId`，所以再次处理这次提交会发出同样的请求。每条派生命令各自结算：拒绝交给 `@OnRejected`，不会阻止其他命令。其他失败会使这次处理失败，在预算内重试，之后停放这次提交的聚合，与[上文](#what-happens-when-a-handler-throws)相同。目标繁忙属于背压。 |
| 定时器 | 定时器 | 触发的命令的请求 ID 由定时器和它的时间槽构成。拒绝使定时器结束：不再重试，也不会交给任何 `@OnRejected`。失败使定时器保持待触发，从 1 s 起倍增到 5 min 重试，共 10 次（`wow.timers.max-attempts`），之后停放在 `wow.timers.sends` 之下。目标繁忙时一秒后再试，不计入尝试次数，最多连续 20 次；再被拒绝一次就计入一次尝试，所以一直繁忙的目标最终会使定时器停放。 |
| Kafka 命令摄入 | 它的订阅 | 再次投递的命令是重复请求。拒绝既不重试也不停放：发送方要求回执时，以回执发回给它。失败在订阅的预算内重试，之后在这条命令的偏移处停放目标聚合，挡住它后面的命令。目标繁忙属于背压。 |

**存储不可用时**，Saga 的失败属于系统性的，Kafka 命令入口的失败也一样：订阅暂停这个分区并探测，直到命令或事件再次通过，任何聚合都不消耗预算、也不停放（[失败怎样分类](#how-a-failure-is-classified)）。分区暂停期间，节点健康把 `subscriptions` 组件报告为降级；有 `wow-observability` 时，`wow.subscription.paused.partitions` 计数这些分区。

**停放的聚合**稍后会被重试，之后等待运维人员，见[停放的聚合](#parked-aggregates)。

## 测试 {#test-it}

测试通过假端口让处理函数失败。`expectRetried()` 检查运行时在第一次尝试之后会重试这次处理，`expectParked(attempt)` 检查它在那次尝试之后会停放；两者都返回处理函数抛出的异常。它们像订阅一样对异常分类：先按处理函数的 `@Retry`，再按各模块的分类器和 Wow 自己的规则。应用自己的 `FailureClassifier` Bean 属于运行时，由宿主的测试检查。再处理一次同一次提交，即 `whenRedelivered()`，就能说明重试是安全的：

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/ShipmentNotifierTest.kt#test -->

```kotlin
@Test
fun `a failed notification should be asked for again with the same key`() = reactorSpec(handlers) {
    val order = paid()
    failures += IllegalStateException("The mail service is down.")
    whenEvent(OrderShipped, by = ShipOrder::class) {
        // Within the notifier's own budget, the runtime asks again.
        expectRetried().assert().isInstanceOf(IllegalStateException::class.java)
    }
    // The retry hands the same commit again: the same key, so the mail service sends it once.
    whenRedelivered()
    sent.single().third.assert().isEqualTo("ShipmentNotifier.onOrderShipped:${order.ref}@3:0")
}
```

等待和停放后的重试归运行时：测试 DSL 每次 `whenEvent` 或 `whenRedelivered` 只调用处理函数一次，并说明运行时接下来会做什么，而不去做。
