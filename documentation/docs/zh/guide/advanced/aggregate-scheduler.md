---
title: 聚合调度器
description: 按命名聚合缓存 Reactor Scheduler，并区分线程池、处理 group 与顺序边界。
outline: deep
---

# 聚合调度器

`AggregateSchedulerSupplier` 为命令和事件分发器提供 Reactor `Scheduler`。默认实现按 **materialized named aggregate** 缓存一个 parallel Scheduler；它不是“每个聚合 ID 一条线程”，也不是分布式锁。

## Supplier 合同

```kotlin
interface AggregateSchedulerSupplier : GracefullyStoppable {
    fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler
    fun forceStop()
}
```

`DefaultAggregateSchedulerSupplier(name, parallelism)`：

- 首次访问某个命名聚合时创建 `Schedulers.newParallel("$name-${aggregateName}", parallelism)`；
- 后续访问返回缓存实例；
- `parallelism` 默认是 `Schedulers.DEFAULT_POOL_SIZE`；
- stop 开始后拒绝创建新 Scheduler。

并发调用 `getOrInitialize` 由同一个生命周期 monitor 串行化，测试验证同一 key 只创建一个缓存实例。

## Scheduler 与处理 group 不同

分发器还有自己的 `parallelism`，用于计算聚合 ID 的 group key。两层概念不要混用：

| 概念 | 决定什么 |
| --- | --- |
| Supplier `parallelism` | 每个命名聚合 Scheduler 的 Reactor worker 数 |
| Dispatcher `parallelism` | `groupBy` 的逻辑并发 group 数；默认来自 `MessageParallelism.DEFAULT_PARALLELISM` |
| `toGroupKey()` | 把 AggregateId 映射到其中一个 group |

`AggregateDispatcher` 对每个 group 使用串行处理链，并让不同 group 并发：

```text
exchange
  → groupBy(aggregateId.id.hashCode().mod(dispatcherParallelism))
  → 每个 group 内 concatMap
  → group 在该命名聚合的 Scheduler 上执行
```

所以同一聚合 ID 在同一分发器实例中稳定映射到同一 group；不同 ID 可能映射到不同 group 并发，也可能哈希碰撞后共享串行 group。

## 能依赖与不能推断的范围

可以从默认实现得到：

- 同一 materialized named aggregate 复用 Scheduler；
- 同一 `aggregateId.id` 在同一 dispatcher parallelism 下映射到同一 group；
- 同一 group 中 exchange 串行进入 handler。

不能据此推断：

- AggregateId 永久绑定某个物理线程；
- 跨 Runtime 实例、Broker partition 或服务的全局顺序；
- 同一事件匹配的多个 handler 函数按声明顺序执行；
- 串行调度能替代 EventStore 版本冲突检查；
- handler 副作用天然幂等。

写入一致性最终仍由聚合边界与 EventStore append 约束；外部处理顺序还取决于 Bus Adapter、分区和消费组。

## 所有权与停止

Supplier 拥有它缓存的 Scheduler：

- `stopGracefully()` 原子关闭新建入口，取出全部缓存并调用 `disposeGracefully()`；结果被 cache，多个观察者共享同一次终止；
- `forceStop()` 取得同一终止快照并立即 `dispose()`；
- force 可以接管进行中的 graceful disposal；完成后仍拒绝新 Scheduler。

`CompositeEventDispatcher` 把 `BorrowedAggregateSchedulerSupplier` 交给子分发器。借用视图的 stop/force 是 no-op，只有父组件关闭真实 Supplier，避免重复释放。

这套生命周期由[运行时生命周期](./runtime-lifecycle.md)的逆序清理和全局 deadline 约束。

## 调优边界

增加 worker 或 dispatcher group 只会提高可并发执行的上限，也会增加队列、上下文切换和下游并发。热点单聚合仍在同一 group 内串行。调优前至少分别观察：

- 每个命名聚合的队列与处理延迟；
- handler 是 CPU、非阻塞 I/O 还是误用了阻塞调用；
- EventStore/Broker/外部系统的并发上限；
- 停机时 Scheduler 排空是否落在 Runtime deadline 内。

不要从线程数计算生产吞吐保证；使用目标版本、硬件、参数和真实后端的基准/故障证据。

## 验证与源码

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.scheduler.AggregateSchedulerSupplierTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.event.dispatcher.CompositeEventDispatcherLifecycleTest"
```

- [`AggregateSchedulerSupplier`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/scheduler/AggregateSchedulerSupplier.kt)
- [`AggregateDispatcher`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/messaging/dispatcher/AggregateDispatcher.kt)
- [`MessageParallelism`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/messaging/dispatcher/MessageParallelism.kt)
- [事件分发管线](../event/dispatch.md)：分发、函数并发与确认
