---
title: 事件处理器
description: 用普通事件处理器执行已提交事件的副作用，并明确函数匹配、响应式完成、幂等、顺序、重投与失败边界。
outline: deep
---

# 事件处理器

事件处理器在领域事件已经追加后执行普通应用副作用。它不是源聚合事务的延伸：处理失败不会撤销事件，处理成功也不会让副作用成为权威事件历史。

## 何时使用普通事件处理器

适合使用 Processor 的工作包括发送通知、写审计记录、调用外部服务、更新集成状态或失效缓存。若事件需要生成其他聚合的后续命令，选择 [Saga](./saga.md)。

普通事件处理器拥有自己的副作用合同：完成条件、幂等键、允许的重试、顺序要求以及失败后的恢复方式都必须在这个边界内明确。

## 定义事件函数

`@EventProcessor` 同时是 Spring 组件标记。框架注册以下两类方法：

- 约定名 `onEvent` / `onStateEvent`；
- 显式标注 `@OnEvent` / `@OnStateEvent` 的其他方法名。

```kotlin
@EventProcessor
class OrderNotificationProcessor(
    private val notificationPort: NotificationPort,
) {
    @OnEvent
    fun onOrderCreated(event: DomainEvent<OrderCreated>): Mono<Void> =
        notificationPort.sendCreated(
            operationId = event.id,
            order = event.body,
        ).then()
}
```

第一个参数可以是事件体、`DomainEvent<T>` 或 `DomainEventExchange<T>`。后续参数由 exchange 或服务容器注入；只有需要消息元数据时才升级到完整消息或 exchange。

## 领域事件与状态事件

`@OnEvent` 消费领域事件总线中的事件事实。`@OnStateEvent` 消费带有事件流和聚合最新状态的状态事件，函数仍以具体领域事件为第一个参数，并可把状态作为后续参数注入：

```kotlin
@EventProcessor
class OrderStateNotifier {
    @OnStateEvent
    fun onCreated(event: OrderCreated, state: OrderState): Mono<Void> =
        notifyCurrentStatus(event.orderId, state.status)
}
```

只依赖事件事实时使用 `@OnEvent`；确实需要该次状态事件携带的聚合状态时才使用 `@OnStateEvent`。不要为了读取查询数据而把普通处理器改成状态事件函数。

## 过滤与函数匹配

注册器先按事件体类型和聚合 topic 选择函数。未在 `@OnEvent` 或 `@OnStateEvent` 中显式提供聚合名时，topic 从事件体的模型元数据解析；需要限制来源时显式声明：

```kotlin
@OnEvent("order")
fun onOrderCreated(event: OrderCreated): Mono<Void> = handle(event)
```

Dispatcher 对事件流中的每条事件逐一查找所有匹配函数。类型或 topic 不匹配的函数不会执行；补偿重投还会限定到失败记录中的目标函数。等待 `EVENT_HANDLED` 时应尽量同时指定 `contextName`、`processorName` 和 `functionName`，避免同阶段的其他函数提前满足等待。

## 响应式副作用

返回值代表副作用的真实完成边界。框架可以把同步函数、挂起函数、`Mono`、`Flux`、Reactive Streams `Publisher` 与 Kotlin `Flow` 适配到统一的响应式调用链。

```kotlin
@OnEvent
fun onOrderCreated(event: DomainEvent<OrderCreated>): Mono<Void> =
    integrationRepository.upsert(
        operationId = event.id,
        value = mapOrder(event.body),
    ).then()
```

不要在函数内调用 `block()` 或启动脱离返回值的 `subscribe()`；否则 Dispatcher 无法观察完成、错误和重试。必须调用阻塞 API 时使用 `@Blocking`，让已有 accessor 把调用移到允许阻塞的调度器。

## 幂等、顺序与重投

事件处理不是 exactly-once 副作用。即时重试、持久补偿重放或运维重发都可能再次调用同一函数。

- 使用 `event.id`、事件流 ID 或“聚合 ID + 版本”等稳定值作为目标系统的幂等键；进程内“已处理”集合不是持久幂等。
- 一个收到的事件流在 Dispatcher 内通过 `concatMap` 按事件顺序处理。
- 同一事件匹配的多个函数通过 `flatMap` 执行；不要依赖函数间顺序。
- 不要推断不同聚合、不同实例或外部系统之间存在全局顺序。目标要求顺序时，持久化源聚合与版本并显式处理缺口。
- 重投必须复用原事件身份和既定幂等规则，不能把“重新调用函数”误当成一次新业务事实。

## 失败、重试与补偿入口

函数应通过返回的 publisher 传播错误。吞掉错误只会让 Dispatcher 把失败当成成功；抛出错误也不能回滚已追加的源事件。

当前运行时 `RetryableFilter` 只对标记为 `RECOVERABLE` 的错误执行有界即时重试，默认最多重试 3 次、最小退避 2 秒。即时重试只存在于本次处理调用中，不提供进程崩溃后的恢复。

必须最终处理的失败应进入 Compensation。启用该模块时，最终仍失败的事件函数可形成持久失败记录；`@Retry` 提供分类和重试规格，`@Retry(enabled = false)` 可关闭该函数的持久记录。完整生命周期属于“事件补偿”（计划路径：`/zh/guide/event/compensation`），本页不复制其状态机、配置、Dashboard 或部署说明。

## 测试与完成标志

先把函数当作响应式单元测试，验证副作用参数与幂等键：

```kotlin
@Test
fun `uses event id as operation id`() {
    val event = orderCreatedDomainEvent(id = "event-1")

    StepVerifier.create(processor.onOrderCreated(event))
        .verifyComplete()

    verify { notificationPort.sendCreated("event-1", event.body) }
}
```

依赖 topic、状态注入、函数选择、重试或真实外部持久化时，再增加 metadata/Dispatcher 或集成测试。

**完成标志：** 正常与失败 publisher 都可被观察；重复输入不产生重复业务效果；顺序假设已有验证；匹配函数完成后产生 `EVENT_HANDLED`。该阶段只证明这个 Processor 函数完成，不证明其他下游分支或外部系统最终一致，详见[完成语义](../command/completion.md)。
