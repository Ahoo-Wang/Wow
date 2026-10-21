---
title: Kafka
description: Wow 用 Kafka 做什么、每个上下文拥有哪些主题、怎样创建主题并确定它们的大小，以及守住传输保证的设置。
---

# Kafka

Kafka 承载跨越服务边界的东西：上下文发布的事件流、其他上下文发给它的命令，以及返回的回执。在服务内部，日志就是总线，Kafka 不参与。本页说明服务用到哪些主题、怎样创建它们，以及怎样配置传输。

## 服务什么时候需要 Kafka {#when-a-service-needs-kafka}

满足下面任何一条，服务就需要 Kafka：

- 它为其他上下文或外部系统发布聚合的事件流；
- 它订阅另一个上下文发布的事件流；
- 它的 Saga 向另一个上下文发命令，或接收那些命令的回执；
- 其他上下文向它发命令；
- 某个聚合类型的命令经 Kafka 路由，以便持久摄入。

Saga 向另一个上下文发命令的节点，没有命令传输就不会启动。本地开发时，`wow.topics.in-memory: true` 把主题保存在 JVM 里，同一进程中的服务不需要 broker 就能互相访问。

类路径上有 `wow-kafka` 模块并设置了 `wow.kafka.bootstrap-servers` 时，传输会被装配。eShop 的 ordering 服务用它的 `kafka` profile 连接：

<!-- sample: example/eshop/ordering/ordering-server/src/main/resources/application.yaml#kafka -->

```yaml
wow:
  kafka:
    # Creates the topics the service owns when they are missing, for trying it out; production validates them, the
    # default (decision D168).
    topics:
      ddl: auto
    bootstrap-servers: localhost:9092
    # Execute the commands other contexts send to wow.eshop-ordering.commands (the payment's), which has as many
    # partitions as the context's log (wow.log.partitions, 64 by default).
    command-intake: true
```

## 主题 {#topics}

每个上下文拥有自己的主题；其他上下文的主题归它们自己。

| 主题 | 由谁写入 | 由谁读取 |
| --- | --- | --- |
| `wow.<context>.<aggregate>.state` | 本上下文的 Kafka Sink，每个发布的聚合每次提交一条记录 | 其他上下文的订阅、外部消费者 |
| `wow.<context>.commands` | 其他上下文的 Saga 和定时器；对经 Kafka 路由的聚合类型，还有本上下文自己的节点 | 本上下文 |
| `wow.<context>.receipts` | 其他上下文，在它们拒绝本上下文发出的命令、或提交了本上下文要求回执的命令时 | 本上下文的 Saga |

聚合默认会被发布；它也可以不发布、发布时不带状态，或者指定另一个主题。`wow.kafka.topic-prefix` 在每个主题名前加一个前缀，例如 `staging.`，写入和读取两侧都加。每条记录都是以聚合为键的信封，所以一个聚合的记录保持顺序（[线上协议](../reference/protocols#kafka-envelope)）。

### 分区 {#partitions}

- **命令主题的分区数与它所属上下文的日志完全相同**（`wow.log.partitions`）。命令在主题中的分区，就是它的聚合在日志中的分区，所以读到它的节点就是拥有这个聚合的节点。分区数不同时，命令会到达不拥有其聚合的节点，所以读取命令主题的节点在启动时检查分区数，不一致就拒绝启动。绝不要给命令主题增加分区。
- **状态主题或回执主题**可以有任意数量的分区，服务运行时也可以增加分区。读取的节点每隔 `wow.kafka.topics.partition-refresh`（30 秒）查找新分区，并从开头读取它们，不需要重启。

### 创建主题 {#creating-topics}

启动时，节点按 `wow.kafka.topics.ddl` 检查它的上下文拥有的主题：

| `ddl` | 启动时 |
| --- | --- |
| `validate`（默认） | 只要它的某个主题缺失，或者命令主题的分区数与日志不完全相同，节点就不启动。消息会列出每个这样的主题。 |
| `auto` | 节点先用日志的分区数、`wow.kafka.topics.replication-factor`、`wow.kafka.topics.min-insync-replicas` 和 `wow.kafka.topics.configs.*` 创建缺失的主题，然后校验。它从不修改已存在的主题。 |
| `none` | 节点什么都不检查，只检查它读取的命令主题的分区数仍与日志相同。 |

开发和测试用 `auto`。生产环境中自己创建主题并保持 `validate`，它在首次启动时就能发现缺失的主题。对 eShop 的 ordering 上下文：

```bash
kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.commands --partitions 64
kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.receipts --partitions 64
kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.order.state --partitions 64
```

节点从不因为读取主题而顺带创建它，所以对尚不存在的事件流是等待，而不是按 broker 的默认分区数创建。

## 持久性 {#durability}

命令主题上接收的命令，必须挺过一个 broker 的丢失。只有当主题的副本数和 `min.insync.replicas` 都至少为 2 时才成立。启动时，节点对它的上下文中每个低于此要求的命令主题、以及会创建这种主题的配置发出警告，除非处于开发 profile（`dev`、`local`、`test` 或 `demo`）。

无论 `wow.kafka.properties` 怎么设，生产者都是幂等的，并等待所有同步副本确认（`acks=all`）。

## 持久的命令摄入 {#durable-command-intake}

默认情况下，命令经集群通道路由到它的聚合的属主，`ACCEPTED` 只表示它通过了准入：执行它之前停止的节点会丢掉它。对于一旦被接收就不能丢的命令，例如异步摄入或吸收负载高峰，用 `wow.aggregates.[<context>.<aggregate>].routing: kafka` 让它们的聚合类型经 Kafka 路由：

- 准入这种命令的节点把它写进上下文的命令主题，并等待 broker 确认。只等待 `accepted` 的请求在 broker 确认后以 202 返回；从此这条命令就是持久的。
- 聚合所在分区的属主从主题读取并执行它，所以一个聚合的命令保持顺序。更长的等待，例如 `snapshot`，照常继续。
- broker 在等待的超时时间内没有确认时，等待在 `ACCEPTED` 结束，结果为失败。用同一个请求 ID 重试不会被决定两次。

只有在边缘准入的命令才走这条路。Saga 或定时器发出的命令本来就是持久的，因为发送方会一直重试直到它提交。

有聚合类型经 Kafka 路由的上下文，无论 `wow.kafka.command-intake` 怎么设都会读取它的命令主题。否则，在应当执行其他上下文发来的命令的服务上，设置 `wow.kafka.command-intake: true`。

## 读取其他上下文的事件流 {#reading-other-contexts-streams}

服务读取其他上下文的主题时不使用消费组。它的租约把主题的分区分配给它的节点，方式与分配日志的分区相同；偏移量保存在服务自己的检查点存储里，与它的其他检查点放在一起。每个节点、每个主题一个消费者，供给读取该主题的每个订阅。所以在 Kafka 里没有需要管理、重置或监控的消费组；订阅的位置就是它的检查点。

当分区里已经没有订阅要读取的那个偏移量时（被保留策略删除了，或者主题被重新创建了），这就是一个**偏移量缺口**。`wow.kafka.offset-gap` 决定接下来怎么办：

- `fail`（默认）：读取失败，订阅停在原处并记录错误，直到运维人员移动它的检查点；
- `earliest`：订阅从最早保留的偏移量接着读，跳过丢失的部分。

无论哪种，缺口都会记为一条错误，并计入指标 `wow.kafka.offset.gaps`；为它设置报警。每个主题的保留时间都要长于任何订阅可能落后的时间。

## 客户端设置 {#client-settings}

`wow.kafka.properties.*` 把设置原样传给每个生产者和消费者，例如 `security.protocol` 和 SASL 设置、`client.id` 或 `linger.ms`。少数设置归 Wow 所有、不能覆盖，因为传输的保证建立在它们之上：`acks=all` 的幂等生产者、不使用消费组、不提交任何东西也不自行重置偏移量的消费者，以及读取时不创建主题。
