---
title: 定时命令
description: 从决定或 Saga 中定时一条命令，可以在某个时刻、一段延迟之后或按 cron；用 @OnSchedule 运行周期性任务；它们怎样投递、怎样测试。
outline: deep
---

# 定时命令

有些事应当稍后发生：没有按时支付就自行取消的订单、一次提醒、每晚的一次导入。Wow 把定时命令作为提交的一部分，所以定时与设置它的事件一起写入，要么都写入，要么都不写，并且像日志一样挺过重启和交接。本页从聚合、从 Saga 定时一条命令，并运行一个周期性任务。

## 定时何时到期 {#when-a-schedule-is-due}

`ScheduleRule` 说明何时：

| 规则 | 何时到期 |
| --- | --- |
| `ScheduleRule.at(instant)` | 在某个时刻 |
| `ScheduleRule.after(duration)` | 从设置它的那次提交起，经过一段延迟之后 |
| `ScheduleRule.cron(expression, zone, misfire)` | 每次与一个 cron 表达式匹配时，六个字段或 `@daily` 这样的宏，按 `zone` 读取（默认 UTC） |

定时属于一个聚合，挂在一个键下。再次设置这个键会替换它，取消则删除它。

## 从决定中定时 {#schedule-from-a-decision}

命令函数用 `DecideScope` 定时。订单的最后一个商品确认后，它为自己定时：支付宽限期过后取消自己：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#confirm-stock -->

```kotlin
/**
 * Confirms one product. A confirmation the order does not wait for changes nothing: one read again from the
 * stock stream, or one that arrives after another product's shortage cancelled the order (that reservation is
 * released when the inventory hears of the cancellation).
 */
// Decision: the last confirmation produces two events, the item's and the order's.
@OnCommand(
    produces = [OrderStockItemConfirmed::class, OrderStockConfirmed::class],
    schedules = [CancelOrder::class],
)
fun onCommand(command: ConfirmOrderStock, paymentPolicy: PaymentPolicy, scope: DecideScope): Decision {
    val waiting = state.status == OrderStatus.AWAITING_VALIDATION &&
        command.productId in state.products() &&
        command.productId !in state.confirmedProducts
    if (!waiting) {
        return Decision.NoOp
    }
    val confirmed = OrderStockItemConfirmed(command.productId)
    if (state.confirmedProducts + command.productId != state.products()) {
        return Decision.events(confirmed)
    }
    scope.schedule(
        PAYMENT_GRACE_PERIOD,
        ScheduleRule.after(paymentPolicy.gracePeriod),
        CancelOrder(PAYMENT_TIMEOUT)
    )
    return Decision.events(confirmed, OrderStockConfirmed)
}
```

- `scope.schedule(key, rule, command, target)` 把 `command` 发往 `target`，除非给出另一个 ref，否则就是这个聚合。定时与决定的事件一起提交，表现为系统事件 `wow.ScheduleSet`；只定时的决定就只提交这一个事件。
- `scope.cancelSchedule(key)` 取消它。订单在支付后取消 `payment-grace-period`。已经发出的命令不会被撤回：由它的目标的状态来把关。
- 函数要在 `@OnCommand(schedules = [...])` 里声明被定时命令的类。编译器读不到函数体里的调用，而 Manifest 按完整类型名列出它们。这个声明只是 Manifest 的内容；内核不会拒绝它没有列出的定时。

## 从 Saga 中定时 {#schedule-from-a-saga}

Saga 在它的任何处理函数中把定时记录到 `SagaContext`：`context.schedule(key, rule, request)` 要求请求的目标为自己定时它的命令，`context.cancel(key, target)` 取消它。运行时在处理函数返回的命令之后，按处理函数调用的顺序发送两者，即系统命令 `wow.schedule.Set` 和 `wow.schedule.Cancel`。目标必须存在；否则它拒绝 `wow.schedule.Set`，拒绝交给 Saga 处理 `SetSchedule` 的 `@OnRejected` 处理函数。处理函数在 `@Sends(schedules = [...])` 里声明被定时命令的类：定时一个没有声明的类会使这次处理永久失败，聚合立即停放（[编写 Saga](./sagas#schedule-a-command)）。

## 定时命令怎样投递 {#how-a-scheduled-command-is-delivered}

- 它**至少发送一次，从不提前**，可能稍有延迟：在交接或积压之后，以及在定时器的轮询间隔 `wow.timers.poll-interval`（默认 1 秒）之内。
- 它**只被决定一次**。它的请求 ID 是定时器和时间槽（`timer#slot`），所以再发一次就是目标能认出的重复请求。目标从 `command.schedule` 读取它是为哪个时间槽发送的。
- 目标**按它那时的状态决定它**。拒绝是正常的守卫，不是失败：订单支付之后才到达的取消会被拒绝，定时器就此结束，这个拒绝不交给任何 `@OnRejected` 处理函数。
- 发送它时出现的**技术故障**会重试 `wow.timers.max-attempts` 次（默认 10 次），然后在 `wow.timers.sends` 下停放，由运维人员恢复或跳过（[停放的聚合](./failures#parked-aggregates)）。目标繁忙时一秒后再试，不计入尝试次数（[命令怎样重试](./failures#how-a-command-is-retried)）。
- 它带着定时它的那次提交的作用域，并以那次提交作为它的因果。
- **删除聚合会取消它待执行的定时。** 定时命令不属于定时它的那条命令的等待。

## 运行周期性任务 {#run-a-recurring-job}

不针对某一个聚合的任务，例如每晚的一次导入，是 Saga 的一个 `@OnSchedule` 处理函数。它接收一个 `ScheduleTick`，需要时再接收 `SagaContext`，像 `@OnEvent` 处理函数一样返回它派生的命令。通过上下文，它也可以定时和调用。返回类型没有写出命令时，它像任何 Saga 处理函数一样在 `@Sends(derives = [...])` 里声明它们。每天早上，库存把供应商前一天的到货入库：

<!-- sample: examples/eshop/inventory/inventory-domain/src/main/kotlin/me/ahoo/wow/example/eshop/inventory/domain/stock/SupplierDeliverySaga.kt#job -->

```kotlin
@Blocking
@OnSchedule(name = "receive-deliveries", cron = "0 0 6 * * *")
fun onSchedule(tick: ScheduleTick): List<CommandRequest<ReceiveStock>> {
    val today = Instant.ofEpochMilli(tick.due).atZone(ZoneOffset.UTC).toLocalDate()
    val days = (tick.skipped + 1 downTo 1).map { today.minusDays(it) }
    return days.flatMap { day -> deliveries.deliveredOn(tick.tenantId, day) }.map { delivery ->
        // The stock of the product, in the job instance's tenant: ReceiveStock names its aggregate type.
        CommandRequest.of(ReceiveStock(delivery.quantity), delivery.productId)
    }
}
```

- **触发。** `tenantId` 是实例的租户，`job` 是任务的名字。`slot` 是到期的时间槽，从实例上次启用或改期时起从 1 开始计数，`due` 是到期的时间，单位是纪元毫秒。`skipped` 统计在它之前、因没有节点运行这个任务而错过的时间槽。`partition` 是实例的日志分区，只有 `SHARDED` 任务才有。
- **命令。** 任务的命令发往它的实例的租户，就像 Saga 的命令发往它的提交的租户：`CommandRequest.of(body, id)` 指的是这个租户中、决定这个命令体的类型的聚合 `id`，如样例所示；`targetTenant` 把命令发往另一个租户。它们的 ID 来自这次触发的执行，所以再处理一次这次触发会发出相同的请求。
- **实例。** 每个任务由上下文内置的聚合 `<context>.schedule` 保存：`SYSTEM` 或 `TENANT` 实例的 ID 就是任务的名字，`SHARDED` 任务每个分区一个实例。规则每次到期时它提交一次触发，任务的订阅 `<processor>.<function>`（或函数的 `@Name`）把触发交给处理函数，像任何订阅一样重试和停放。

`@OnSchedule` 设置任务：

| 设置 | 含义 |
| --- | --- |
| `name` | 任务的名字，在它的上下文中唯一：字母、数字、`_` 和 `-` |
| `cron` | 六个字段，按 Spring 的 `CronExpression` 读取：`0 0 6 * * *` 是每天 06:00。也接受 `@daily` 这样的宏 |
| `zone` | 读取 `cron` 所用的时区，默认 UTC |
| `scope` | `SYSTEM`（默认）：一个实例，在默认租户中，宿主在启动时启用。`TENANT`：每个租户一个实例，由该租户中的系统命令 `wow.schedule.Enable` 创建；宿主从不启动它，每个租户都可以覆盖规则、时区和 misfire。`SHARDED`：每个日志分区一个实例，在默认租户中，宿主在启动时启用，由分区的属主为它自己分区的聚合运行，每个时间槽各触发一次，并设置 `tick.partition` |
| `misfire` | `COALESCE`（默认）：在没有节点运行任务期间错过的时间槽，只运行最近的一个，`skipped` 统计其余的。`CATCH_UP`：每个错过的时间槽都运行 |

宿主在 `wow.schedules.<name>` 下配置任务，而且只在这里配置，不需要重新构建：

```yaml
wow:
  schedules:
    receive-deliveries:
      cron: "0 30 5 * * *"
      retry:
        max-attempts: 3
```

- `cron`、`zone` 和 `misfire` 覆盖注解的规则。
- `enabled: false` 在每次启动时停用 `SYSTEM` 或 `SHARDED` 任务的实例。
- `retry.*` 和 `parked.*` 是任务订阅的重试，覆盖处理函数的 `@Retry` 和 `wow.subscription.*`（[失败与重试](./failures#give-a-handler-its-own-budget)）。任务的订阅改写在 `wow.subscriptions` 下，或者为没有处理函数运行的任务写设置，都会使节点在启动时停止。

实例自己的规则优先于宿主的规则：`TENANT` 任务中租户的规则，或者运维人员用 `reschedule` 设的规则。它由实例保存，所以重启后依然有效。

任务的实例也接收 HTTP 命令：`POST /<context>/wow.schedule/{id}/<command>`，命令为 `enable`、`reschedule`、`disable`，id 为任务名。它们是 Wow 的系统命令，公开端口默认从不放行：没有命令授权器的服务拒绝它们（403 `wow.auth.forbidden`），有授权器时由授权器决定。在 CoSec 下，动作是 `schedule/<job>` 上的 `<context>:wow.schedule.Enable`（以及其他任务命令），由策略授予运维人员（[动作名称](../operate/security#action-names)）。任务自己的命令、Saga 发出的命令以及 ops 在内部端口发送的命令不受影响。

## 测试 {#test-it}

测试一个会定时的决定，就随聚合的事件一起期望这个定时（[测试领域代码](./testing#scheduled-commands)）。任务用 `scheduleSpec` 针对真实的任务聚合测试：启用实例，拨动时钟，用 Saga 测试的期望检查它的处理函数派生的命令：

<!-- sample: examples/eshop/inventory/inventory-domain/src/test/kotlin/me/ahoo/wow/example/eshop/inventory/domain/stock/SupplierDeliverySagaTest.kt#test -->

```kotlin
@Test
fun `the morning job should receive yesterday's deliveries`() = scheduleSpec(
    handlers,
    start = Instant.parse("2026-10-20T00:00:00Z"),
) {
    enable("receive-deliveries")
    advanceTime(6.hours) {
        expectCommands(
            stock.ref(TENANT, "mug") to ReceiveStock(40),
            stock.ref(TENANT, "cap") to ReceiveStock(12),
        )
    }
    asked.assert().containsExactly(LocalDate.parse("2026-10-19"))
}
```

- `advanceTime` 拨动时钟并交出到期的触发；一个都没有到期时它失败，所以写错的规则不会悄悄通过。`ticks` 列出已交出的触发。
- `reschedule` 和 `disable` 发送实例的其他命令；`enable` 接收 `tenantId`、`rule` 或 `partition`，用于其他范围。
- `whenTick` 不经过任务聚合，把测试自己的触发交给处理函数：时钟要拨很久才到的时间槽，或者设了 `skipped` 的触发。
