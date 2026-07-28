---
title: 聚合调度器
description: 为每个聚合提供专用 Reactor 调度器，控制并发执行和资源分配。
---

# 聚合调度器

聚合调度器为每个聚合提供专用的 Reactor Scheduler，用于控制并发执行和资源分配。

## 调度器供应器

聚合调度器供应器为每个聚合提供或创建专用的调度器。它继承自 `GracefullyStoppable`，
以便运行时在关闭时释放所有缓存的调度器。

```kotlin
interface AggregateSchedulerSupplier : GracefullyStoppable {
    fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler
    // 继承方法：stopGracefully(): Mono<Void>
}
```

### 默认实现

`DefaultAggregateSchedulerSupplier` 为每个具象化聚合延迟创建一个
`Schedulers.newParallel` 并缓存。构造函数接收 `name`（作为调度器名前缀）和可选的
`parallelism`（默认 `Schedulers.DEFAULT_POOL_SIZE`）；它还实现了 `ParallelismCapable` 与 `Named`。

```kotlin
class DefaultAggregateSchedulerSupplier(
    override val name: String,
    override val parallelism: Int = Schedulers.DEFAULT_POOL_SIZE
) : AggregateSchedulerSupplier,
    ParallelismCapable,
    Named {

    private val schedulers: MutableMap<MaterializedNamedAggregate, Scheduler> = ConcurrentHashMap()

    override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler =
        schedulers.computeIfAbsent(namedAggregate.materialize()) { _ ->
            Schedulers.newParallel("$name-${namedAggregate.aggregateName}", parallelism)
        }

    override fun stopGracefully(): Mono<Void> {
        // 在优雅关闭时释放所有缓存的调度器
    }
}
```

首次为某个命名聚合调用时会创建一个名为 `{supplier-name}-{aggregateName}` 的并行调度器
（例如 `order-service-order`）；后续对同一聚合的调用返回缓存实例。

## 分发器如何使用调度器

每个 Wow 分发器（命令、领域事件、状态事件、投影、Saga、快照）都从供应器获取其按聚合类型的调度器，
并通过 `publishOn(scheduler)` 处理。在该调度器内，`AggregateDispatcher` 按聚合 ID 哈希将消息分组到
`parallelism` 通道；同一通道内的事件通过 `concatMap` 串行化，但同一聚合类型内的不同聚合 ID 可能跨通道并发处理。

```kotlin
// EventStreamDispatcher —— 每个命名聚合创建一个分发器
override fun newAggregateDispatcher(namedAggregate: NamedAggregate): AggregateEventDispatcher {
    return AggregateEventDispatcher(
        namedAggregate = namedAggregate,
        messageFlux = ...,
        scheduler = schedulerSupplier.getOrInitialize(namedAggregate), // 专用调度器
        // ...
    )
}
```

在 `AbstractAggregateEventDispatcher` 内部，分组后的 Flux 被发布到该调度器：

```kotlin
messageFlux
    .groupBy { it.toGroupKey(parallelism) }   // 按 parallelism 通道分散
    .flatMap { grouped -> grouped.publishOn(scheduler) ... } // 同一聚合类型 -> 同一调度器
```

这是 Wow **按聚合实例串行处理** 保证的基础：同一聚合类型的调度器内，`AggregateDispatcher`
将聚合 ID 哈希到 `parallelism` 通道，同一通道内的事件通过 `concatMap` 串行化，
但同一聚合类型内的不同聚合 ID 可能跨通道并发处理。

## 为什么需要按聚合的专用调度器？

| 关注点 | 按聚合的调度器如何解决 |
|---|---|
| **顺序性** | 同一聚合类型的事件共享一个调度器。在该调度器内，`AggregateDispatcher` 将聚合 ID 哈希到 `parallelism` 通道；**同一通道**内的事件通过 `concatMap` 串行化，但**不同聚合 ID** 的事件可能跨通道并发处理。顺序保证是按聚合实例的，而非跨实例。 |
| **隔离性** | 不同聚合类型（例如 `order` 与 `cart`）获得各自的调度器，慢速类型不会阻塞另一类型。 |
| **背压** | 每个命名聚合的调度器有自己的队列；竞争以聚合类型为单位受限，而非全局。 |
| **资源控制** | `parallelism` 限制每个命名聚合类型的工作线程数，防止某个热点类型耗尽所有 CPU。 |
| **优雅关闭** | `stopGracefully()` 在应用关闭时释放所有缓存的调度器。 |