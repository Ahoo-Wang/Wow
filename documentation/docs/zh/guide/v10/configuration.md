---
title: V10 配置项
description: Wow V10 Spring Boot 宿主的全部 wow.* 配置项及默认值，取自 WowProperties、QueryProperties、KafkaProperties 与 ClickHouseProperties。
outline: deep
---

# V10 配置项

V10 宿主读取 `wow.*` 命名空间，由 Spring Boot 从 `application.yaml`、环境变量或命令行绑定。没有任何配置项沿用 9.x 的含义。本页列出 `wow-spring-boot` 四个属性类的全部配置项：`WowProperties`（`wow.*`）、`QueryProperties`（`wow.query.*`）、`KafkaProperties`（`wow.kafka.*`）与 `ClickHouseProperties`（`wow.clickhouse.*`）。时长使用 Spring Boot 的写法（`500ms`、`10s`、`1m`、`PT10S`）。

名字本身含点的键（订阅名或聚合类型）要加方括号，在 YAML 中加引号：

```yaml
wow:
  subscriptions:
    "[OrderStockSaga.onOrderShipped]":
      start: beginning
  aggregates:
    "[example.cart]":
      tenant: shop
      routing: kafka
```

## 服务与节点

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.service` | `spring.application.name` | 服务名：用于租约组、等待来源与 Manifest。两者必须设置其一 |
| `wow.node.id` | 主机名加随机后缀 | 本节点在服务各节点中的 ID；不设置时每次启动都是新的 |
| `wow.node.address` | 本机地址与 `wow.internal.port` | 其他节点访问本节点内部端口所用的 `host:port` |
| `wow.internal.port` | `9090` | 内部端口：集群通道与等待信号。`0` 由系统选择，节点绑定后得知 |
| `wow.internal.host` | 所有网卡 | 内部端口绑定的地址 |
| `wow.internal.connect-timeout` | `1s` | 对端接受集群通道或信号发送连接的时限 |
| `wow.internal.signal-queue-capacity` | `1024` | 每个来源节点排队的等待信号数，超过即丢弃 |
| `wow.contexts.include` | 空：类路径上的全部上下文 | 节点提供的限界上下文，按名字 |
| `wow.contexts.exclude` | 空 | 从 `include`（或类路径）给出的上下文中去掉的上下文 |

宿主提供类路径上领域模块的全部上下文。类路径上有多个上下文时，用 `wow.contexts.*` 选择其中一部分：按配置切换上下文的模块化单体，或在一个 JVM 中启动多个服务、每个只包含自己上下文的端到端测试（eShop 的 `EshopSharedJvmTest`）。节点只为选中的上下文构建：它们的模型、Manifest 片段、路由与处理器；没选中的上下文就是另一个服务的，经对端访问。类路径上不存在的名字、既包含又排除的名字，以及一个上下文都不剩的选择，都会让启动失败，并列出类路径上的上下文。

## 后端与日志

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.backend` | `auto` | `auto`、`memory`、`postgres` 或 `mongo`。`auto` 在类路径有 `wow-postgres` 且应用有 `DataSource` Bean 时选 PostgreSQL，否则在有 `wow-mongo` 且有 `MongoClient` Bean 时选 MongoDB，否则选内存 |
| `wow.mongo.database` | `wow` | MongoDB 上日志与各存储所在的数据库 |
| `wow.log.partitions` | `64` | 每个上下文日志的分区数；日志有条目后即固定 |
| `wow.log.append-queue.enabled` | 后端预设：内存与 MongoDB 开，PostgreSQL 关 | 是否按分区批量追加 |
| `wow.log.append-queue.max-batch` | `64` | 一批最多的追加数 |

## 集群

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.cluster.router` | 内存后端为 `local`，其余为 `partitioned` | 命令在哪里执行：`partitioned` 在聚合所在日志分区的属主上执行，`local` 在收到命令的节点上执行。内存后端拒绝 `partitioned` |
| `wow.cluster.lease-ttl` | `10s` | 分区租约不续约时的有效期 |
| `wow.cluster.renew-interval` | `3s` | 续约间隔；小于 TTL |
| `wow.cluster.member-ttl` | `10s` | 成员没有心跳时的有效期 |
| `wow.cluster.heartbeat-interval` | `3s` | 成员心跳间隔；小于其 TTL |
| `wow.cluster.reconcile-interval` | `1s` | 节点对账自己应持有分区的间隔 |
| `wow.cluster.route-refresh-interval` | `1s` | 读取路由表的间隔；转发失败时立即刷新 |
| `wow.cluster.forward-timeout` | `1s` | 转发等待属主回复的时限，不超过命令自己的截止时间；之后命令在本地执行 |
| `wow.cluster.max-in-flight-per-peer` | `1024` | 每个对端在途的转发数上限；超过的在本地执行 |
| `wow.cluster.drain-timeout` | `10s` | 交出分区时等待其在途命令的时限 |
| `wow.cluster.lease-parallelism` | `16` | 同时进行的租约调用数 |

## 命令与等待

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.wait.default-timeout` | `10s` | 请求未指定超时的命令等待多久 |
| `wow.wait.max-timeout` | `60s` | 最长等待；更长的超时被截到它 |
| `wow.wait.max-hops` | `16` | 等待计划最多的跳数，上限 32。等待链每经过一个 Saga 加两跳 |
| `wow.wait.unknown-contexts` | `allow` | 节点没有 Manifest 的上下文的跳：`allow` 在名字合法时放行，到达处再校验；`reject` 使计划无效 |
| `wow.executor.lane-capacity` | `1024` | 一个聚合可以排队的命令数 |
| `wow.state-cache.capacity` | `4096` | 每个上下文在命令之间缓存的聚合状态数；`0` 不缓存 |
| `wow.tenant.default` | `(0)` | 没有租户的调用方（例如匿名调用方）的租户 |
| `wow.aggregates.[<context>.<aggregate>].tenant` | 无 | 为该类型的所有聚合固定租户，与调用方无关。节点不提供的类型在启动时被拒绝 |
| `wow.aggregates.[<context>.<aggregate>].routing` | `cluster` | 该类型命令的路由方式：`cluster` 按 `wow.cluster.router`；`kafka` 经所在上下文的命令主题，接受即持久（决定 D143，见[部署](./deployment.md#kafka-router)）。`kafka` 需要 `wow.kafka.bootstrap-servers`，并开启该上下文的命令入口。节点不提供的类型在启动时被拒绝 |

应用未设置 `spring.lifecycle.timeout-per-shutdown-phase` 时，宿主把它设为 `wow.wait.max-timeout + wow.cluster.drain-timeout`，让正在停止的节点有足够的时间；应用设置了更小的值时保留它，并给出警告。

## 订阅与定时器

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.subscription.max-in-flight` | `1024` | 一个订阅在一个分区中，从检查点到已取得的最后一条之间最多持有的条目数 |
| `wow.subscription.checkpoint-interval` | `1s` | 每个订阅保存检查点的间隔 |
| `wow.subscription.stop-timeout` | `10s` | 停止时等待在途处理函数的时限 |
| `wow.subscription.retry.max-attempts` | `5` | 失败的处理函数总共被调用的次数，之后停放其聚合 |
| `wow.subscription.retry.initial-backoff` | `100ms` | 第一次失败后的等待 |
| `wow.subscription.retry.max-backoff` | `5s` | 两次尝试之间最长的等待 |
| `wow.subscription.retry.multiplier` | `2.0` | 每次等待比上一次长多少倍 |
| `wow.subscription.retry.timeout` | 无 | 处理函数一次尝试最长运行多久；超过的被取消，算一次失败的尝试 |
| `wow.subscription.read-retry.*` | `100ms` 至 `5s`，`2.0` | 日志读取或存储写入失败的退避，重试直到节点停止（不使用 `max-attempts`） |
| `wow.subscriptions.[<subscription>].start` | 按种类的默认值 | `beginning`、`end` 或 ISO-8601 时刻：订阅在没有检查点的分区中从哪里开始。节点没有该名字的订阅时启动被拒绝 |
| `wow.subscriptions.[<subscription>].retry.*` | `wow.subscription.retry` | 该订阅停放前自己的进程内预算：`max-attempts`、`initial-backoff`、`max-backoff`、`multiplier`、`timeout`。没给出的项取全局的。节点没有该名字的订阅时启动被拒绝 |
| `wow.timers.poll-interval` | `1s` | 每个持有的分区读取即将到期定时器的间隔 |
| `wow.timers.lookahead` | `30s` | 提前读取定时器的时长 |
| `wow.timers.max-attempts` | `10` | 发送定时命令的技术故障最多尝试的次数，之后停放在 `wow.timers` 下 |
| `wow.timers.expires-after` | 无 | 设置后，到期超过这个时长才被发现的定时器被取消（`EXPIRED`）而不是发送 |

## HTTP

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.http.request-facts` | 空 | 复制到命令 headers、从而写入事件流的请求头 |
| `wow.http.client-facts` | `true` | 公开端口是否记录每条命令的客户端地址与 `User-Agent`（`remote_ip`、`user_agent`）：个人信息，一经写入无法删除 |
| `wow.http.errors.[<code>].status` | Manifest 中的值 | 某个错误码的 HTTP 状态码 |
| `wow.http.global-errors` | `true` | 应用自己的路由出错时是否也以问题详情应答（[错误码](./http-api.md#错误码)） |
| `wow.http.manifest.enabled` | `true` | 是否提供 `GET /wow/manifest` |

公开端口是 Spring Boot 的 `server.port`。

## CoSec

类路径上有 `wow-cosec` 且应用没有定义 `CallerResolver` 时，由 `CoSecCallerResolver` 解析调用方（[两级调用方](./http-api.md#两级调用方)）：

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.cosec.tenant-level.roles`、`wow.cosec.tenant-level.policies` | 空 | 持有这些角色或策略的主体是租户级调用方：没有所有者，可以触达本租户的全部聚合 |
| `wow.cosec.cross-scope.roles`、`wow.cosec.cross-scope.policies` | 空 | 持有这些角色或策略的主体可以用 `Wow-Scope` 在别的作用域中操作 |
| `wow.cosec.authorizer.enabled` | `false` | 是否由 CoSec 的授权决定每条命令（动作级授权） |

## 查询

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.query.enabled` | `true` | 是否接入查询路由与网关 |
| `wow.query.http.max-list-size` | `1000` | `list` 的最大条数 |
| `wow.query.http.default-list-size` | `100` | 未指定 limit 的 `list` 的条数 |
| `wow.query.http.max-page-size` | `100` | 最大页大小 |
| `wow.query.http.max-page-window` | `10000` | `paged` 查询能翻到的深度 |
| `wow.query.http.max-filter-nodes` | `128` | 筛选条件最多的节点数 |
| `wow.query.http.max-filter-values` | `1000` | 筛选条件最多的值数 |
| `wow.query.http.allow-expensive-operators` | `true` | 是否允许昂贵的操作符 |
| `wow.query.http.max-residual-groups` | `10000` | 后端无法自行执行聚合的 `HAVING` 或指标排序时，核心为计算它读取的最多分组数 |
| `wow.query.http.count-limit` | `10000` | 计数与分页总数的上界 |
| `wow.query.http.allow-exact-count` | `false` | 查询是否可以要求精确总数 |
| `wow.query.http.timeout` | `30s` | HTTP 查询可以执行的时长 |
| `wow.query.http.unindexed-sort-expensive` | `false` | 后端不走索引的排序是否算作昂贵操作符 |
| `wow.query.in-process-timeout` | `30s` | 没有预算的进程内查询的超时 |
| `wow.query.require-explicit-entry` | `false` | 查询是否必须说明自己是 HTTP 还是进程内；未说明时视为进程内 |
| `wow.query.require-authenticated-scope` | `false` | HTTP 查询是否需要能固定模型所需作用域的认证作用域；关闭时信任声明的作用域 |
| `wow.query.abac.enabled` | `false` | 是否用调用方的 `abac.<key>` 属性匹配快照的 `tags` 与每个事件流记录的 `tags` |
| `wow.query.abac.require-principal-tags` | `false` | 调用方没有 ABAC 标签的 HTTP 查询是否被拒绝，而不是匹配所有资源 |
| `wow.query.abac.match-missing-tag-key` | `true` | 缺少调用方某个标签键的资源是否作为公共资源匹配 |
| `wow.query.abac.attribute-prefix` | `abac.` | ABAC 读取的调用方属性前缀 |
| `wow.query.sensitivity.display-comparable` | `true` | 筛选与排序能否比较 `DISPLAY` 值 |
| `wow.query.index.ddl` | `auto` | 快照后端如何管理 `@QueryIndex` 的索引：`auto` 创建或重建，`validate` 只报告，`none` 什么也不做 |
| `wow.query.dataset.config-directory` | `config` | 其 `wow/dataset/` 下放数据集文件的工作目录，优先于类路径的 `META-INF/wow/dataset/` |

限制设为 `0` 表示不限制。

## Kafka

类路径上有 `wow-kafka` 时读取；设置 `wow.kafka.bootstrap-servers` 之前什么也不接入。没有 Kafka 时，`wow.topics.in-memory: true`（默认 false）把节点的命令主题和发布的流放在它的 JVM 里，仅供本地开发；同时设置了 `wow.kafka.bootstrap-servers` 时节点拒绝启动。

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.kafka.bootstrap-servers` | 无 | broker 地址。设置后，节点发布自己的流、读取其他上下文的流与回执、向其他上下文发送命令 |
| `wow.kafka.properties.*` | 空 | 原样传给每个生产者与消费者（`security.protocol`、`client.id`、`linger.ms` 等），传输保证所依赖的设置除外 |
| `wow.kafka.topic-prefix` | 空 | 加在每个主题名前，例如 `staging.` 得到 `staging.wow.shop.order.state` |
| `wow.kafka.poll-timeout` | `200ms` | 消费者一次 poll 最长等待的时间 |
| `wow.kafka.command-intake` | `false` | 节点承载的每个上下文是否执行其主题 `wow.<context>.commands` 中的命令；该主题必须存在且分区数为 `wow.log.partitions` |
| `wow.kafka.topics.ddl` | `validate` | 节点启动时如何管理本节点各上下文拥有的主题（决定 D168）：`validate` 在主题缺失、或命令主题的分区数不恰好等于 `wow.log.partitions` 时拒绝启动；`auto` 先按日志的分区数创建缺失的主题；`none` 只检查节点读取的命令主题的分区数。见[部署](./deployment.md#kafka) |
| `wow.kafka.topics.replication-factor` | broker 的默认值 | `auto` 创建主题时的副本数 |
| `wow.kafka.topics.configs.*` | 空 | `auto` 创建主题时的主题设置（`retention.ms`、`min.insync.replicas` 等） |
| `wow.kafka.topics.partition-refresh` | `30s` | 节点多久检查一次所读主题（其他上下文的流、本上下文的回执）新增的分区，也是其消费者的 `metadata.max.age.ms`。新分区在大约两倍于此的时间内开始被读取，无需重启 |

## ClickHouse

类路径上有 `wow-clickhouse` 时读取；设置 `wow.clickhouse.endpoint` 之前什么也不接入。

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wow.clickhouse.endpoint` | 无 | 服务器的 HTTP 端点，例如 `http://localhost:8123` |
| `wow.clickhouse.username` | `default` | 用户 |
| `wow.clickhouse.password` | 空 | 密码 |
| `wow.clickhouse.database` | `default` | 表所在的数据库 |
| `wow.clickhouse.sink.enabled` | `true` | 是否把 Manifest 发布的流写入 ClickHouse |
| `wow.clickhouse.sink.ddl` | `auto` | `auto`（创建缺少的，再校验）、`validate` 或 `none` |
| `wow.clickhouse.sink.batch-size` | `1000` | 一次插入最多的记录数 |
| `wow.clickhouse.sink.deduplication-window` | `1000` | 每张表记住的最近插入数，用于丢弃重试的插入 |
| `wow.clickhouse.sink.aggregates.<context>.<aggregate>.history.enabled` | `true` | 是否保留每次提交；关闭时只保留当前状态 |
| `wow.clickhouse.sink.aggregates.<context>.<aggregate>.history.ttl` | 无 | 历史保留多久；不设置时永久保留 |
| `wow.clickhouse.query.enabled` | `true` | 是否从 ClickHouse 提供数据集 |
| `wow.clickhouse.query.metadata-ttl` | `1m` | 数据集表的列信息复用多久后重新读取 |
| `wow.clickhouse.query.settings.<name>` | 空 | 每个数据集查询使用的服务器设置，例如 `max_memory_usage` |
