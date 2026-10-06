---
title: Kafka
description: 使用 Kafka 承载分布式命令、领域事件与状态事件总线。
---

# Kafka

`wow-kafka` 实现分布式 `CommandBus`、`DomainEventBus` 和 `StateEventBus`。当同一限界上下文跨进程部署、消息必须由 Kafka 消费者组分发时使用它；单进程开发或测试优先使用 `in_memory`，不要为“将来可能扩容”提前引入 Broker。

模块存在只代表实现可用。只有 classpath、`wow.kafka.enabled`、具体 `*.bus.type` 和 Kafka 连接配置同时满足条件，Starter 才会装配对应总线。

## 架构概述

Wow 负责把框架消息转换为 Kafka record，并把收到的 record 包装成可确认的 exchange。Kafka 负责主题、分区、复制、保留、消费者组和 offset 持久化；应用仍负责 Broker 运维、主题策略和消息处理的业务幂等性。

### 高层流程

发送路径是 `CommandGateway`/事件发布器 → Wow 总线 → Kafka；接收路径是 Kafka → Wow exchange → 命令、事件、投影或 Saga 处理器。`send`、`receiver` 均保持 Reactor 非阻塞契约。

### 类层级

自 9.3.0 起，`KafkaCommandBus`、`KafkaDomainEventBus`、`KafkaStateEventBus` 就是建在 `KafkaTransport` 之上的核心 `TransportCommandBus`、`TransportDomainEventBus`、`TransportStateEventBus`（见[传输 SPI](../command/internals/transport.md#传输-spi)）。`KafkaTransport` 负责生产者、消费者、就绪锚定与接收重试；核心总线负责编码、解码、键与主题校验和解码失败策略。三个总线只有主题转换器不同。也可以在已有传输上构建总线：`KafkaCommandBus(transport, topicConverter, decodeFailureHandler)`。

### 三种总线，三种主题类型

| 总线 | 选择属性 | 默认后缀 |
|---|---|---|
| 命令 | `wow.command.bus.type=kafka` | `.command` |
| 领域事件 | `wow.event.bus.type=kafka` | `.event` |
| 状态事件 | `wow.eventsourcing.state.bus.type=kafka` | `.state` |

## 端到端消息流

发送端把消息标记为只读，使用聚合 ID 作为 record key，并等待 `KafkaSender` 的发送结果。接收端按订阅聚合计算主题，建立消费者组，解码后产生 exchange；处理器完成后由 exchange 确认 offset。

这不是 exactly-once 业务保证。Broker 重投、处理器失败和进程退出仍要求命令与事件处理具备幂等性。

## 安装

直接使用模块：

```kotlin
implementation("me.ahoo.wow:wow-kafka")
```

使用 Starter 时请求实际 Gradle capability：

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.wow:kafka-support")
    }
}
```

不要同时重复声明 capability 和 `wow-kafka`，除非依赖解析确有需要。

## 配置

下面是三种总线都由 Kafka 承载时的最小显式配置：

```yaml
spring:
  application:
    name: order-service

wow:
  command:
    bus:
      type: kafka
  event:
    bus:
      type: kafka
  eventsourcing:
    state:
      bus:
        type: kafka
  kafka:
    bootstrap-servers:
      - localhost:9092
```

`wow.kafka.bootstrap-servers` 没有默认值。`enabled=true`、`topic-prefix=wow.`、`receiver.prefetch-batches=1`、`receiver.max-deferred-commits=500`、`receiver.retry-attempts=3`、`receiver.retry-backoff=10s`、`receiver.decode-failure-strategy=FAIL`。`close-timeout`（9.3.0 起）限定关闭 producer 时等待缓冲记录刷出的时长；未设置时取 `wow.shutdown-timeout`，因此关闭不会超出运行时的停机期限（Kafka 客户端自身会无限等待）。手动构建的 `KafkaTransport` 使用传入 `SenderOptions` 的 `closeTimeout`。

### 总线类型选择

三种总线可独立选择 `kafka`、`redis`、`in_memory` 或 `no_op`。不要因为 capability 在 classpath 上就推断它正在承载全部总线；以三个 `*.bus.type` 和启动后的 Bean 类型为准。

### SenderOptions 和 ReceiverOptions

`wow.kafka.properties` 同时进入 producer/consumer，`wow.kafka.producer` 与 `wow.kafka.consumer` 分别覆盖同名通用项。Starter固定字符串 serializer/deserializer；Broker 认证、TLS、acks、超时和消费策略仍使用 Kafka 原生属性。

### 接收端重试策略

接收流按 `retry-attempts` 与 `retry-backoff` 重试连续失败；重试耗尽后错误才到达 dispatcher，并让运行时停止。自 9.3.0 起重试由核心的 `TransportFailurePolicy` 定义，Kafka 与 Redis 相同（Bean `kafkaTransportFailurePolicy`；`KafkaReceiverPolicy` 不再有 `retrySpec`）。`prefetch-batches`、`max-deferred-commits` 必须大于 0；重试次数和退避不能为负，非法值在启动装配时被拒绝。

自 9.3.0 起，Kafka 的 `RetriableException` 及其子类（超时、leader 变更、broker 不可用）注册为 `RECOVERABLE`（`KafkaRecoverableExceptionProvider`），`RetryableFilter` 与事件存储追加结果判定会重试因此失败的发送。

### 解码失败策略

该策略决定 `TransportDecodeFailureHandler` Bean（`TransportDecodeFailureHandler.FAIL` 或 `.ACKNOWLEDGE`；9.2 及以前的 Bean 类型是 `KafkaRecordDecodeFailureHandler`）。value 不是该总线的消息 JSON，或 key、topic 与解码出的消息不一致，都算解码失败。默认 `FAIL`：坏 record 终止当前接收流并进入重试。`ACKNOWLEDGE` 会确认并跳过坏 record，可能造成不可恢复的数据丢失，只应在有死信、审计和重放方案时启用。

## 主题命名规则

默认名称为 `${topic-prefix}${contextAlias}.${aggregateName}.command|event|state`。应用可提供 `CommandTopicConverter`、`EventStreamTopicConverter` 或 `StateEventTopicConverter` 覆盖命名；主题创建、分区数、复制因子与保留策略不由这些转换器管理。

## 分区策略

record key 是 `aggregateId.id`，Kafka 的分区器据此把同一聚合的 record 路由到同一分区，从而利用分区内顺序。更改主题分区数或自定义分区器会改变映射，必须按 Kafka 原生语义评估迁移与顺序边界。

## 自动配置

`KafkaAutoConfiguration` 需要 Wow 启用、`wow-kafka` 类存在、`wow.kafka.enabled=true`，并按三个总线选择属性分别创建实现。

### Bean 装配

自动配置提供主题转换器、`ReceiverOptionsCustomizer`、`KafkaReceiverPolicy`、解码失败处理器，以及被选中的分布式总线。`@ConditionalOnMissingBean` 只覆盖源码标注的扩展点，不代表所有 Kafka Bean 都能被任意同类型 Bean 替换。

### ConditionalOnKafkaEnabled

`wow.kafka.enabled=false` 会关闭 Kafka 自动配置，但不会自动把 `*.bus.type=kafka` 改成其他实现。关闭时必须同时选择可用总线，否则运行时缺少所需分布式 Bean。

### ReceiverOptionsCustomizer

使用现有 `ReceiverOptionsCustomizer` 注入 Kafka 原生 receiver 调整；不要为已由 `wow.kafka.consumer` 表达的普通 client 属性再写定制器。

## 生产者优化

批量、压缩、acks 和重试属于 Kafka producer 配置。先用 Broker/producer 指标证明瓶颈，再通过 `wow.kafka.producer` 调整；Wow 不替代 Kafka 对这些值的校验和兼容规则。

## 消费者优化

吞吐量由分区数、消费者实例、处理耗时、poll/commit 设置共同决定。优先调整 Kafka 原生 consumer 参数与处理器并发证据，不要仅提高 `prefetch-batches` 掩盖慢处理器。

## 消费者组

`MessageSubscription.receiverGroup` 成为 Kafka `group.id`。消费者组、分区分配与再均衡由 Kafka 管理；部署前应验证每个运行时实例使用预期 group，并确认不会把两个逻辑处理器误放进同一竞争组。

## 关键设计决策

这些约束来自当前 `KafkaTransport`、`TransportMessageBus` 与测试，不是通用 Kafka 教程。

### 1. Kafka 层的字符串序列化

Wow 在 record value 中写入框架 JSON，在 Kafka client 层使用字符串 serializer/deserializer。消息 wire 兼容性由 Wow/Jackson 模型演进负责，Kafka 只保存字节序列。

### 2. 只读消息保护

发送前调用 `message.withReadOnly()`，防止同一消息对象在进入异步发送后继续修改；它不提供跨进程防篡改或消息签名。

### 3. 手动偏移量确认

exchange 的 `acknowledge()` 提交处理完成的 offset，`max-deferred-commits` 保留乱序完成产生的间隙：提交永远不会越过最早一条未确认的记录。未确认消息可被重新投递，这是预期的 at-least-once 恢复语义。

已确认、待提交的 offset 达到 `max-deferred-commits` 时，Reactor Kafka 会暂停拉取；因此总线在达到这个数量时立即发起一次提交（Reactor Kafka 的 `commitBatchSize`，上限为 `max-deferred-commits`；通过 `ReceiverOptionsCustomizer` 设置的更小值保持不变）。其余情况按 `commitInterval`（默认 5 秒）定期提交。只有在更早的记录仍在处理或提交正在进行时，拉取才会暂停。这个上限按消费者计算：一个 Wow 接收端就是一个消费者，它的 `max-deferred-commits` 统计其订阅的全部 topic 与分区上的已确认 offset。提交触发在 `ReceiverOptionsCustomizer` 之后应用，因此总与最终的 `maxDeferredCommits` 一致；更大的 `commitBatchSize` 会被截到该值，并记录一次 INFO 日志。

::: info 9.2.3 起的变化
9.2.2 及以前默认 `max-deferred-commits=1` 且没有上述提交触发：每确认一条记录，消费者都要暂停到下一次定期提交，所以接收端大约每个 `commitInterval`（5 秒）只处理一次拉取。现在默认值为 500，即 Kafka 默认的 `max.poll.records`。显式配置过 `max-deferred-commits` 的部署保留原值，但达到该数量后会立即提交，而不再暂停等待。
:::

### 4. 发送反馈

每次发送等待该 record 的 `KafkaSender` 结果，producer 异常作为 Reactor error 返回；`Mono<Void>` 只有在发送反馈完成后终止，不代表下游消费者已处理该消息。

## 监控和可观察性

观察 Broker 可用性、发送错误、consumer lag、再均衡、解码失败和处理器错误。需要 Wow span 时再加入 `opentelemetry-support`；Kafka capability 本身不会配置 exporter 或 Collector。

## 故障排除

以下失败均可由当前属性、实现或测试复现：

- 缺少 `wow.kafka.bootstrap-servers`：`KafkaProperties` 无法完成必要绑定；
- receiver 安全参数越界：启动期间抛出 `IllegalArgumentException`；
- 分区 assignment 的初始 offset 锚定失败：receiver readiness 失败，不应被报告为已就绪；
- JSON 解码失败：按 `FAIL` 重试，或按明确配置的 `ACKNOWLEDGE` 跳过。

### 常见问题

先区分连接、主题、消费者组与消息内容四类问题，再查看对应的 Kafka client 日志和 Broker 状态。

#### 1. 连接超时

核对 `bootstrap-servers`、DNS、TLS/SASL 和网络策略。Wow 不在连接前复制 Kafka client 的地址或凭据校验。

#### 2. 未知主题或分区

核对转换后的完整主题名以及 Broker 的自动建主题策略。不要把 classpath 中存在 Kafka 模块当作主题已经创建的证据。

#### 3. 频繁的消费者再均衡

检查实例 churn、处理时间、`max.poll.interval.ms` 和 group 配置；再均衡归 Kafka 所有，Wow 只响应分区 assignment/revoke。

#### 4. 消息解码失败

保留原始 record、topic/partition/offset 和异常。默认 `FAIL` 便于阻止静默数据丢失；切换为 `ACKNOWLEDGE` 前先准备隔离与重放路径。

### 监控指标

以 Kafka client/Broker 的 producer error、request latency、consumer lag、rebalance 与 commit 指标为主；Wow 指标与 trace 只补充框架处理阶段。

## 完整配置示例

```yaml
wow:
  command:
    bus:
      type: kafka
  event:
    bus:
      type: kafka
  eventsourcing:
    state:
      bus:
        type: kafka
  kafka:
    bootstrap-servers: [kafka-0:9092, kafka-1:9092]
    topic-prefix: 'wow.'
    producer:
      acks: all
    consumer:
      auto.offset.reset: earliest
    receiver:
      prefetch-batches: 1
      max-deferred-commits: 500
      retry-attempts: 3
      retry-backoff: 10s
      decode-failure-strategy: FAIL
```

这些 producer/consumer 值是示例，不是所有集群的推荐模板；由应用按 Kafka 版本、耐久性目标和容量测试决定。

## 最佳实践

- 显式选择每种总线，不依赖默认值表达生产架构；
- 为主题、消费者组、保留和重放建立运维清单；
- 保持处理器幂等，并在故障注入中验证重投；
- 变更分区、topic converter 或解码策略前先做兼容与数据恢复演练。

聚焦检查：

```bash
./gradlew :wow-kafka:check
```

该命令验证模块单元与契约测试，不证明你的 Kafka 集群、ACL、主题策略或目标环境装配可用。

## 相关主题

下一步阅读[基础设施配置](../../reference/config/infrastructure.md)建立 Broker、配置、恢复和准入证据；需要 trace 时阅读 [OpenTelemetry](./opentelemetry.md)。
