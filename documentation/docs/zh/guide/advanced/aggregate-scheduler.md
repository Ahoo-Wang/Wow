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

每个 Wow 分发器（命令、领域事件、状态事件、投影、Saga、快照）都从供应器获取其按聚合的调度器，
并通过 `publishOn(scheduler)` 保证同一聚合实例的所有消息都在同一调度器上处理
（进而实现按聚合串行处理）。该装配集中在按聚合的分发器工厂中：

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
    .flatMap { grouped -> grouped.publishOn(scheduler) ... } // 同一聚合 -> 同一调度器
```

这是 Wow **按聚合串行处理** 保证的基础：由于一个聚合始终映射到一个缓存的调度器，
针对同一聚合的两个命令不会同时在不同线程上运行，而不同聚合则并行运行。

## 为什么需要按聚合的专用调度器？

| 关注点 | 按聚合的调度器如何解决 |
|---|---|
| **顺序性** | 聚合 `order-001` 的所有事件都在同一调度器上处理，保持发布顺序。 |
| **隔离性** | 慢速聚合（例如巨大的事件重放）不会阻塞其他聚合的共享线程池。 |
| **背压** | 每个聚合的调度器有自己的队列；竞争以聚合为单位受限，而非全局。 |
| **资源控制** | `parallelism` 限制每个聚合的工作线程数，防止某个热点聚合耗尽所有 CPU。 |
| **优雅关闭** | `stopGracefully()` 在应用关闭时释放所有缓存的调度器。 |