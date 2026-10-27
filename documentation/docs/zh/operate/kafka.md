---
title: Kafka
description: Wow 用 Kafka 做什么、每个上下文拥有哪些主题、怎样创建主题并确定它们的大小，以及守住传输保证的设置。
---

# Kafka

Kafka 承载跨越服务边界的东西：其他上下文发给某个上下文的命令、返回的回执，以及它为读者发布的事件流。在服务内部，日志就是总线，Kafka 不参与。本页说明服务用到哪些主题、怎样创建它们，以及怎样配置传输。

## 服务什么时候需要 Kafka {#when-a-service-needs-kafka}

满足下面任何一条，服务就需要主题传输，生产环境中即 Kafka：

- 它的 Saga 向另一个上下文发命令；
- 其他上下文向它发命令（`wow.kafka.command-intake.enabled: true`）；
- 某个聚合类型的命令经 Kafka 路由，以便持久摄入；
- 它订阅另一个上下文发布的事件流；
- 它以 `wow.ops.publish.enabled: true` 发布失败台账。

没有传输的节点遇到上面任何一种情况都会拒绝启动，并指出是什么需要传输。它发布的流不算这种需要：默认每个聚合都发布，这并不意味着有人读取。没有传输时，节点不发布其中任何一个，并在启动时说明一次：

```text
This service runs without a topic transport, so it does not publish its streams [<context>.<aggregate>, …]. To publish them, configure Kafka (wow.kafka.bootstrap-servers) or another transport provider.
```

什么也不发布的节点则记录 `This service needs no topic transport: it publishes no stream and takes no topic.`。回执没有单独的需要：它们为发往另一个上下文的命令而返回，而这些命令本来就需要传输。等待和 Saga 的同步调用走内部 HTTP 端口，不经过 Kafka。

`wow.transport` 选择节点的主题传输。默认的 `auto` 取已配置的传输（设置了 `wow.kafka.bootstrap-servers` 就是 Kafka）。没有配置时，开发中的节点（`wow.dev-profiles` 中的某个 profile 处于激活状态，或者内部端口只绑定回环地址）把主题保存在 JVM 里：同一进程中的服务不需要 broker 就能互相访问，它承载的每个上下文都接收自己的命令，并且在 ops 上下文运行于同一个 JVM 时（或设置 `wow.ops.publish.enabled: true` 时）发布失败台账。其他节点没有主题。`wow.transport=memory` 有意把主题保存在 JVM 里；开发以外会被拒绝。这些主题在 JVM 的整个生命周期里保留发给它们的每条记录：持续负载下会占满堆，所以只用于试用服务，不能用于压测或部署。

类路径上有 `wow-kafka` 模块并设置了 `wow.kafka.bootstrap-servers` 时，传输会被装配。eShop 的 ordering 服务用它的 `kafka` profile 连接：

<!-- sample: example/eshop/ordering/ordering-server/src/main/resources/application.yaml#kafka -->

```yaml
wow:
  kafka:
    bootstrap-servers: localhost:9092
    # Execute the commands other contexts send to wow.eshop-ordering.commands (the payment's), which has as many
    # partitions as the context's log (wow.log.partitions, 64 by default).
    command-intake:
      enabled: true
```

## 主题 {#topics}

每个上下文拥有自己的主题；其他上下文的主题归它们自己。

| 主题 | 由谁写入 | 由谁读取 |
| --- | --- | --- |
| `wow.<context>.<aggregate>.state` | 本上下文的 Kafka Sink，每个发布的聚合每次提交一条记录 | 其他上下文的订阅、外部消费者 |
| `wow.<context>.commands` | 其他上下文的 Saga 和定时器；对经 Kafka 路由的聚合类型，还有本上下文自己的节点；只要它发布失败台账，还有 ops 对其台账的操作（`wow.ledger.*`） | 本上下文 |
| `wow.<context>.receipts` | 其他上下文，在它们拒绝本上下文发出的命令、或提交了本上下文要求回执的命令时 | 本上下文的 Saga |

聚合默认会被发布；它也可以不发布、发布时不带状态，或者指定另一个主题。`wow.kafka.topic-prefix` 在每个主题名前加一个前缀，例如 `staging.`，写入和读取两侧都加。每条记录都是以聚合为键的信封，所以一个聚合的记录保持顺序（[线上协议](../reference/protocols#kafka-envelope)）。

### 分区 {#partitions}

- **命令主题的分区数与它所属上下文的日志完全相同**（`wow.log.partitions`）。命令在主题中的分区，就是它的聚合在日志中的分区，所以读到它的节点就是拥有这个聚合的节点。分区数不同时，命令会到达不拥有其聚合的节点，所以读取命令主题的节点在启动时检查分区数（`wow.ddl` 为 `auto` 或 `validate` 时），不一致就拒绝启动。节点不接收其命令、只接收台账命令的上下文，只检查主题是否存在：分区数不同时，节点发出警告，并在没有台账命令的情况下运行。绝不要给命令主题增加分区。
- **状态主题或回执主题**可以有任意数量的分区，服务运行时也可以增加分区。读取的节点每隔 `wow.kafka.topics.partition-refresh`（30 秒）查找新分区，并从开头读取它们，不需要重启。

### 创建主题 {#creating-topics}

启动时，节点按 `wow.kafka.ddl` 检查它的上下文拥有的主题；没有设置时它跟随 `wow.ddl`，存储和主题共用一个设置：

| `ddl` | 启动时 |
| --- | --- |
| `auto`（默认） | 节点先用日志的分区数、`wow.kafka.topics.replication-factor`、`wow.kafka.topics.min-insync-replicas` 和 `wow.kafka.topics.configs.*` 创建缺失的主题，然后校验。它从不修改已存在的主题。 |
| `validate` | 只要它的某个主题缺失，或者命令主题的分区数与日志不完全相同，节点就不启动。消息会列出每个这样的主题。发布失败台账的服务即使没有其他上下文调用它，也需要自己的命令主题，用于 ops 的 `wow.ledger.*` 操作。 |
| `none` | 节点既不创建也不检查：主题由别人管理。 |

开发和测试用 `auto`。生产环境中自己创建主题，并设置 `wow.ddl: validate`（或只设 `wow.kafka.ddl`），它在首次启动时就能发现缺失的主题。对 eShop 的 ordering 上下文：

```bash
kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.commands --partitions 64
kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.receipts --partitions 64
kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.order.state --partitions 64
```

节点从不因为读取主题而顺带创建它，所以对尚不存在的事件流是等待，而不是按 broker 的默认分区数创建。但 broker 会这样做：开启 `auto.create.topics.enable`（Kafka 的默认值）时，向不存在的主题写入的生产者会按 broker 的默认分区数创建它，通常是一个。接收命令的服务遇到这样创建的命令主题会拒绝启动，只接收台账命令的服务则在没有台账命令的情况下运行并发出警告。在 broker 上关闭它，让 Wow 创建自己的主题（`ddl: auto`）或检查它们（`validate`）。

## 持久性 {#durability}

命令主题上接收的命令，必须挺过一个 broker 的丢失。只有当主题的副本数和 `min.insync.replicas` 都至少为 2 时才成立。启动时，节点对它的上下文中每个低于此要求的命令主题、以及会创建这种主题的配置发出警告，除非节点处于开发中：`wow.dev-profiles` 中的某个 profile 处于激活状态（默认为 `dev`、`local`、`test` 和 `demo`），或者它的内部端口只绑定回环地址。

无论 `wow.kafka.properties` 怎么设，生产者都是幂等的，并等待所有同步副本确认（`acks=all`）。

## 持久的命令摄入 {#durable-command-intake}

默认情况下，命令经集群通道路由到它的聚合的属主，`ACCEPTED` 只表示它通过了准入：执行它之前停止的节点会丢掉它。对于一旦被接收就不能丢的命令，例如异步摄入或吸收负载高峰，用 `wow.aggregates.[<context>.<aggregate>].routing: kafka` 让它们的聚合类型经 Kafka 路由：

- 准入这种命令的节点把它写进上下文的命令主题，并等待 broker 确认。只等待 `accepted` 的请求在 broker 确认后以 202 返回；从此这条命令就是持久的。
- 聚合所在分区的属主从主题读取并执行它，所以一个聚合的命令保持顺序。更长的等待，例如 `snapshot`，照常继续。
- broker 在等待的超时时间内没有确认时，等待在 `ACCEPTED` 结束，结果为失败。用同一个请求 ID 重试不会被决定两次。

只有在边缘准入的命令才走这条路。Saga 或定时器发出的命令本来就是持久的，因为发送方会一直重试直到它提交。

有聚合类型经 Kafka 路由的上下文，无论 `wow.kafka.command-intake.enabled` 怎么设都会读取它的命令主题。否则，在应当执行其他上下文发来的命令的服务上，设置 `wow.kafka.command-intake.enabled: true`。ops 服务对停放聚合的操作也以同样方式到达，即服务命令主题上的 `wow.ledger.*` 命令。发布失败台账的服务在摄入关闭时也会自行读取它们：它只执行这些命令，越过其他命令（[ops 服务与控制台](./ops-console)）。此时它的命令主题必须存在，分区数与它的日志相同。

## 读取其他上下文的事件流 {#reading-other-contexts-streams}

服务读取其他上下文的主题时不使用消费组。它的租约把主题的分区分配给它的节点，方式与分配日志的分区相同；偏移量保存在服务自己的检查点存储里，与它的其他检查点放在一起。每个节点、每个主题一个消费者，供给读取该主题的每个订阅。所以在 Kafka 里没有需要管理、重置或监控的消费组；订阅的位置就是它的检查点。

当分区里已经没有订阅要读取的那个偏移量时（被保留策略删除了，或者主题被重新创建了），这就是一个**偏移量缺口**。`wow.kafka.offset-gap` 决定接下来怎么办：

- `fail`（默认）：读取失败，订阅停在原处并记录错误；
- `earliest`：订阅从最早保留的偏移量接着读，跳过丢失的部分。

无论哪种，缺口都会记为一条错误，并计入指标 `wow.kafka.offset.gaps`；为它设置报警。每个主题的保留时间都要长于任何订阅可能落后的时间。

要恢复因缺口而停下的订阅，设置 `wow.kafka.offset-gap: earliest` 并重启它的节点：读取从最早保留的偏移量继续。跳过的区间对这个订阅来说就丢失了，并仍计入 `wow.kafka.offset.gaps`。之后把这个选项改回 `fail`，以保持严格的默认行为。如果这种丢失不可接受，而发布过的内容在别处仍然完整，就改为用一个新的订阅名重建。

## 客户端设置 {#client-settings}

`wow.kafka.properties.*` 把设置原样传给每个生产者和消费者，例如 `security.protocol` 和 SASL 设置、`client.id` 或 `linger.ms`。少数设置归 Wow 所有、不能覆盖，因为传输的保证建立在它们之上：`acks=all` 的幂等生产者、不使用消费组、不提交任何东西也不自行重置偏移量的消费者，以及读取时不创建主题。
