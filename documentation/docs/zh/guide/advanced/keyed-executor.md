---
title: 按键执行器
description: 自 9.3.0 起分发器如何执行消息——每个运行时一组按 CPU 核数的工作线程、每个聚合 ID 一个邮箱、未完成消息有上限，以及由此能得到与不能得到的顺序保证。
outline: deep
---

# 按键执行器

自 9.3.0 起，一个 `WowRuntime` 的全部分发器——命令、领域事件、状态事件、投影、无状态 Saga、快照——都在同一个 `KeyedExecutor` 上执行消息。它取代了 9.2 中按聚合类型创建的 `Schedulers.newParallel(核数)` 线程池（`AggregateSchedulerSupplier`），以及按 `64 × 核数` 分组串行的 `groupBy`（`MessageParallelism`）。

## 模型

```text
传输接收器
  → 准入（运行时活动、本地优先回执）
  → 消息所属聚合 ID 的邮箱                  每个分发器、每个活跃聚合 ID 一个
  → 在运行时的某个工作线程上执行处理函数      工作线程数默认等于 CPU 核数
```

- **每个运行时一组工作线程。** 线程数取决于硬件，与聚合类型数、分发器数无关。16 核上 20 种聚合只用 16 个分发线程，而不是每种分发器 20 × 16 个。
- **每个聚合 ID 一个邮箱。** 同一聚合 ID 的消息逐条执行，顺序与分发器收到的顺序一致；不同聚合 ID 的消息并行执行。邮箱只在有消息时存在，并在创建时分配到一个工作线程上执行（在工作线程间轮转，与 9.2 中 `publishOn` 分组分配到的线程相同）。每个工作线程有自己的无锁 FIFO 队列，忙碌的工作线程无需唤醒即可取到下一个邮箱。
- **公平轮转。** 处理函数同步完成时，一个邮箱每轮最多执行 `throughput` 条消息，然后排到同一工作线程上其他邮箱之后，热点聚合因此不会饿死其他聚合。
- **等待不占用工作线程。** 处理函数在等待时——非阻塞 I/O，或版本冲突后的命令重试退避——会释放工作线程，只推迟自己的邮箱。9.2 中一个冲突的聚合会在整个退避期间阻塞哈希到同一组的所有聚合。
- **未完成消息有上限。** 每个分发器最多持有 `max-in-flight` 条未完成的消息（执行中或在邮箱中排队），消息完成后以小批量（窗口的 1/16）向传输请求更多，长时间未完成的消息（慢聚合的 I/O）不会拖住其他消息。慢的分发器因此对传输施加背压（Kafka 暂停拉取，本地优先发送方等待本地准入），而不是无限缓冲。
- **协程在工作线程上恢复。** 由分发器调用的 `suspend` 或 `Flow` 消息函数在执行器的工作线程上运行协程，而不是 `Dispatchers.Default`。函数返回前，邮箱不会开始该聚合的下一条消息，所以跨挂起点也保持按聚合串行。在分发器之外调用（例如测试）时，这类函数仍在 `Dispatchers.Default` 上运行。

## 配置

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.dispatch.workers` | 可用处理器数 | 运行时全部分发器共享的工作线程数 |
| `wow.dispatch.max-in-flight` | `256` | 单个分发器停止请求新消息前可持有的未完成消息数 |
| `wow.dispatch.throughput` | `16` | 工作线程在转向其他聚合前，一轮执行同一聚合的消息数 |

不使用 Spring 时，把执行器交给运行时；运行时拥有它，并在全部组件停止后关闭它：

```kotlin
val runtime = WowRuntime(
    components = listOf(commandDispatcher, eventDispatcher),
    shutdownTimeout = Duration.ofSeconds(30),
    shutdownQuietPeriod = Duration.ZERO,
    keyedExecutor = KeyedExecutor(workers = 8, maxInFlight = 256),
)
```

用不属于运行时的 `RuntimeContext` 准备的分发器（例如单元测试）使用 `KeyedExecutor.shared`，这是一个进程级、守护线程的执行器。

## 顺序范围

可以保证：

- 同一分发器收到的同一聚合 ID 的消息，按收到的顺序逐条处理；
- 同一聚合 ID 的下一条消息，在上一个处理函数返回的 `Mono` 完成后才开始。

不能保证：

- 聚合 ID 与线程绑定（相邻两条消息可能在不同工作线程上执行）；
- 跨分发器、跨运行时实例、跨 broker 分区或跨服务的顺序；
- 匹配同一事件的多个函数按声明顺序执行；
- 替代 EventStore 版本冲突检查，或保证处理函数副作用的幂等。

写一致性仍然来自聚合边界与 EventStore 追加；分发器收到消息的顺序来自传输（Kafka 上同一聚合 ID 落在同一分区）。

## 调优

只有 CPU 密集的处理函数才需要调大 `workers`；非阻塞 I/O 不占用工作线程。一个分发器面对大量同时活跃的聚合、且存储延迟较高时，调大 `max-in-flight`；需要限制内存或下游压力时调小。单个热点聚合无论如何配置都是串行的。处理函数不能阻塞：工作线程是 Reactor 的非阻塞线程（与 9.2 的 `newParallel` 线程相同），在其上调用 `block()` 会立即失败；标注 `@Blocking` 的函数会在 `boundedElastic` 上执行，而不会占住少量共享工作线程中的一个。

## 验证与源码

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.execution.KeyedDispatchTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.messaging.dispatcher.AggregateDispatcherTest"
```

- [`KeyedExecutor`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/execution/KeyedExecutor.kt)
- [`AggregateDispatcher`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/messaging/dispatcher/AggregateDispatcher.kt)
- [事件分发管线](../event/dispatch.md)：分发、函数并发与确认
- [运行时生命周期](./runtime-lifecycle.md)：停机顺序与时限
