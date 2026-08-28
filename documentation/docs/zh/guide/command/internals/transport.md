---
title: 命令传输与路由
description: 理解 CommandBus 契约、内存与分布式实现、LocalFirst 双副本准入、Void 路径和 SENT 观察边界。
outline: deep
---

# 命令传输与路由

命令传输负责把 `CommandMessage` 路由为 `ServerCommandExchange`，不负责执行聚合业务规则。如何选择和安装扩展以对应扩展文档与[核心配置参考](../../../reference/config/core.md)为准；本页不复制依赖或配置表。

## CommandBus 契约

`CommandBus` 是 `MessageBus<CommandMessage<*>, ServerCommandExchange<*>>`，固定 `TopicKind.COMMAND`。三个核心动作具有不同边界：

- `send`：返回的 `Mono<Void>` 在具体 transport 接受发送后完成；
- `receive`：按 `MessageSubscription` 返回 exchange 流；
- `receiver`：在消息流之外暴露 transport readiness；`runtimeReceiver` 还允许 WowRuntime 控制 processing admission 和 quiescence。

`LocalCommandBus` 额外暴露订阅者数量和 `sendIfSubscribed`。后者只有在目标本地 receiver 已取得处理准入并确认本次投递仍有效时才能返回 `true`；sink 接受或订阅数本身不够。`DistributedCommandBus` 保留同一发送/接收合同，由后端定义持久化、消费组和 ack 机制。

## InMemory

`InMemoryCommandBus` 以 `NamedAggregate` 为 key 创建 MPSC unicast sink：多个发送者可以并发写入，但每个具名聚合的命令只允许一个消费链。消息发出前被标记为只读，并转换为 `SimpleServerCommandExchange`。

普通 `send` 没有订阅者时会记录 debug 并完成，因此它只证明本进程 sink 的发送动作结束，不证明存在处理者。运行时的 `runtimeReceiver` 维护连接和 processing-open 状态；`sendIfSubscribed` 为每个投递创建 receipt，只有所有目标 receiver 接受运行时准入后才报告本地投递成功。

该实现适合单进程运行和测试，不提供跨进程持久性。

## Kafka

`KafkaCommandBus` 复用 `AbstractKafkaBus`：

- topic 由命令的具名聚合转换；
- record key 是 aggregate ID，value 是只读命令 JSON；
- `send` 等待 Reactor Kafka sender result，producer error 作为 Reactor error 返回；
- `receive` 为订阅的 topic 设置 consumer group，并把 record 转为带 `ReceiverOffset` 的 exchange；
- exchange ack 调用 `ReceiverOffset.acknowledge()`。

`receiver.readiness` 只在 partition assignment 完成并保存保守的初始 offset 边界后完成，避免启动窗口漏消息。解码失败由显式 failure handler 处理；成功处理的消费确认仍属于 exchange ack 边界。

## Redis

`RedisCommandBus` 使用 Redis Streams：`send` 把只读命令 JSON 写入 topic stream 的 `msg` 字段；`receive` 为每个 topic 建立或复用 consumer group，从 `lastConsumed` 读取，并把 `XACK` publisher 放入 exchange。

`receiver.readiness` 在 consumer group 准备完成后触发，但读取还受 processing admission 控制。可选 recovery 会扫描并认领满足条件的 pending record；无法解码的记录会通过 `RedisMessageBusObserver` 报告且保持 pending，不伪装成已成功消费。

Redis 与 Kafka 的发送完成条件不同，二者都不等于聚合已经处理。后端运维、保留、重试与恢复参数属于扩展配置范围，不在本页展开。

## LocalFirst 双副本准入

`LocalFirstCommandBus` 组合一个 local bus 和一个 distributed bus。对本地聚合且 Header 未显式禁用 local-first 的命令，它不会在两条路径中二选一，而是建立受标记约束的双副本流程：

1. 复制命令，标记 `local_first=true`，调用 `localBus.sendIfSubscribed`。
2. 只有 runtime-owned 本地 receiver 已 processing-open 并确认投递仍有效时，receipt 才返回 `true`。
3. 再复制原命令发送到 distributed bus；distributed 副本的 `local_first` 值等于本地投递结果。
4. 合并接收端过滤并 ack 已标记为“本地已处理”的 distributed 副本；本地准入失败、关闭或异常时，该副本保持可处理。

因此 distributed 副本承担回退和可观察记录，`local_first=true` 是经过准入确认的抑制标记，不是仅凭 subscriber count 的猜测。原消息与两个副本使用独立可变 Header，避免两条路径互相改写。

## Void

`LocalFirstCommandBus.send` 对 `isVoid` 命令强制写入 `local_first=false`，跳过本地优先投递并只走 distributed send。`CommandDispatcher` 接收后又用 `filterThenAck` 确认并过滤 `Void` 命令，所以它不会进入聚合 Filter chain，也不会产生 `PROCESSED` 及更晚阶段。

相应地，Gateway 只允许 `supportVoidCommand=true` 的等待计划；内置 `CommandWait.sent` 支持该合同，其他阶段计划会在发送前失败。Void 路径的可观察边界就是 transport 接受，不应把它描述为聚合执行完成。

## `SENT` 对照

`SENT` 表示当前 `CommandBus.send` publisher 成功完成，具体事实取决于实现：

| 实现 | `SENT` 前已发生 | `SENT` 仍不证明 |
| --- | --- | --- |
| InMemory | sink 发射完成；无订阅者也可能完成 | 有处理者、聚合执行、持久化 |
| Kafka | producer send result 成功 | consumer 收到或 ack、聚合执行 |
| Redis | stream add 完成 | consumer group 已处理或 XACK |
| LocalFirst | 本地投递尝试结束，distributed send 完成 | 任一副本已完成聚合处理 |
| Void + LocalFirst | distributed send 完成 | 聚合处理；该路径会被 Dispatcher 过滤 |

`sendAndWaitForSent` 直接根据这个 publisher 合成结果，不依赖回调 Header。需要更强保证时，按[完成语义](../completion.md)选择阶段，而不是重新解释 `SENT`。

## 指标与追踪入口

`MetricCommandBus` 在 decorator 层记录 `command_bus` 的 `send`、`send_if_subscribed` 和接收 stream，保留原 receiver readiness 与 runtime admission。标签来自 context、aggregate、message 和 receiver group；多个 aggregate 会折叠为有界值，避免直接把业务 ID 放入指标。

OpenTelemetry 的 `TracingLocalCommandBus` / `TracingDistributedCommandBus` 在发送边界创建 producer span 并向消息 Header 注入 trace context；`TracingCommandGateway` 另外覆盖 `sendAndWait` 与流式等待，记录完整 waiting span。处理管线的观测还包括 `CommandHandler`、`EventStore` 和 `DomainEventBus` 的各自 decorator；不要只凭一个 bus span 推断端到端完成。

运行时启用方式和 exporter 配置见[可观测性](../../advanced/observability.md)。源码入口：[`CommandBus`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/CommandBus.kt)、[`InMemoryCommandBus`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/InMemoryCommandBus.kt)、[`LocalFirstCommandBus`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/LocalFirstCommandBus.kt)、[`KafkaCommandBus`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-kafka/src/main/kotlin/me/ahoo/wow/kafka/KafkaCommandBus.kt)、[`RedisCommandBus`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-redis/src/main/kotlin/me/ahoo/wow/redis/bus/RedisCommandBus.kt)。
