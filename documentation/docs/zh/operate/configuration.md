---
title: 配置服务
description: "服务的配置怎样生效：Spring Boot 的配置来源与宽松命名、map 的键、时长、哪些必须在各节点间一致、开发环境的默认值，以及发现拼错键的检查。"
outline: deep
---

# 配置服务

服务的配置方式与任何 Spring Boot 应用一样：`application.yaml`、profile、环境变量和命令行参数，前缀为 `wow`。大多数键的默认值都适合生产环境。本页说明配置怎样生效，以及宿主检查什么。所有的键及其类型和默认值，见[配置项](../reference/configuration)。

## 值从哪里来 {#where-the-values-come-from}

宿主读取 Spring Boot 的环境，所以 Spring Boot 认识的每种来源都有效，并按它的优先级生效：命令行参数优先于环境变量，环境变量优先于 profile 专属的文件，后者又优先于 `application.yaml`。名字按宽松规则绑定：`wow.log.max-poll-interval`、`wow.log.maxPollInterval` 和环境变量 `WOW_LOG_MAXPOLLINTERVAL` 是同一个键。

不要把密钥写进文件。部署时由它的密钥存储把每个密钥设成环境变量，例如数据库密码 `SPRING_DATASOURCE_PASSWORD`，eShop 的 Kubernetes 清单从一个 Secret 取得它。

## 怎样写值 {#write-the-values}

| 值 | 写法 |
| --- | --- |
| 时长 | `500ms`、`30s`、`5m`，或 ISO-8601 的 `PT30S` |
| 列表 | YAML 列表，或者来自环境变量的逗号分隔字符串：`WOW_HTTP_REQUESTFACTS=X-Region,X-Channel` |
| 包含点的 map 的键 | 放在方括号里：`wow.aggregates.[eshop-ordering.order].tenant`、`wow.subscriptions.[OrderSummaryProjection.onEvent].start` |

在 YAML 中，给带方括号的键加上引号，让方括号保留为键的一部分：

```yaml
wow:
  subscriptions:
    "[ShipmentNotifier.onOrderShipped]":
      retry:
        max-attempts: 8
```

## 名字会被检查 {#names-are-checked}

对于与它所提供的任何东西都不匹配的名字，宿主不会忽略，而是拒绝启动：`wow.subscriptions` 下没有对应处理函数的订阅、`wow.aggregates` 下节点不提供的聚合类型、`wow.contexts` 下不在任何类路径上的上下文、没有任何东西提供的 ID 生成器。

这类失败，以及宿主无法据以启动的其他取值，都是 `WowConfigurationException`。Spring Boot 在日志末尾把它报告为一份失败分析，而不是一堆 Bean 创建错误：哪里不对、哪个键及其取值、该怎么改。

## 键会被检查 {#keys-are-checked}

Spring 会忽略没有属性绑定的键，所以拼错的键会一声不响地保留默认值。宿主在启动时把每个 `wow.*` 键与类路径上的配置元数据比较：

- 未知的键会阻止启动，并指明它；它是更早版本的键时给出取代它的键，它所属的模块不在类路径上时给出那个模块。
- 在设置的映射之下（`wow.aggregates`、`wow.stores`、`wow.subscriptions`、`wow.schedules`、`wow.callers`、`wow.peers`、`wow.http.errors`），每一项自己的键会一直检查到叶子：否则拼错的 `commands` 会让一个服务可以调用每条命令。

以 Wow 分组命名的环境变量（`WOW_KAFKA_…`、`WOW_CLUSTER_…`：键的第一个词）按 Spring 的映射检查：下划线视为点、忽略连字符（`WOW_COSEC_TRUSTINJECTEDAPP`），或者按 Spring 的旧形式把连字符也写成下划线（`WOW_COSEC_TRUST_INJECTED_APP`），所以过时的 `WOW_KAFKA_TOPICS_DDL` 和 `wow.kafka.topics.ddl` 一样会拒绝启动；你自己的变量请起在这些分组之外的名字。

## 哪些必须在各节点间一致 {#what-must-match-across-nodes}

一个服务的节点组成一个集群，所以有些设置必须在每个节点上相同，并且只能按参考所说的方式改变：

- `wow.service`，服务的名字，默认是 `spring.application.name`。
- `wow.log.partitions`：能整除 4,096 的 2 的幂（默认 64），日志有了条目之后就固定不变。在首次部署之前选定。
- 存储以及每个聚合类型的放置：`wow.backend`、`wow.stores`、`wow.log.store`、`wow.snapshot.*`、`wow.aggregates.*.log`。

## Wow 推导出的值 {#what-wow-derives}

节点从它的环境推导出下面这些值，所以大多数服务一个都不用设置。每个键都会覆盖推导出的值：

| 值 | 推导自 | 键 |
| --- | --- | --- |
| 服务名 | `spring.application.name` | `wow.service` |
| 节点 ID | 每次启动新生成一个 | `wow.node.id` |
| 节点对外公布的地址，以及内部端口绑定的地址 | 本机地址 | `wow.node.address`、`wow.internal.host` |
| 状态缓存 | 最大堆的十分之一，由节点的各上下文分摊 | `wow.state-cache.capacity` |
| 一个存储同时运行的查询数 | 它的连接池：PostgreSQL 为与追加共用的连接池的四分之一（至少 2），或者它自己的 `query-data-source` 的全部；其他为 16 | `wow.query.max-concurrent` |
| 主题的分区、副本数和 `min.insync.replicas` | 日志的分区数；broker 的默认值 | `wow.kafka.topics.*` |
| 后端 | 应用的 `DataSource` 或 `MongoClient` | `wow.backend` |
| 主题传输 | 设置了 `wow.kafka.bootstrap-servers` 时为 Kafka；开发中为 JVM 内的主题 | `wow.transport` |
| 快照保存在哪里，以及主快照存储 | 日志所在的存储；快照存储中的第一个 | `wow.snapshot.stores`、`wow.snapshot.primary` |
| 对端的节点 | 按其契约声明的服务命名的 DNS SRV 记录（[配置对端](../guide/calling-services#configure-a-peer-over-http)） | `wow.peer.discovery`、`wow.peers.<context>.url` |
| 网关之后的客户端地址 | 网关写入的转发头（`server.forward-headers-strategy: framework`） | `server.forward-headers-strategy` |

## 开发与生产 {#development-and-production}

在开发 profile（`dev`、`local`、`test` 或 `demo`，即 `wow.dev-profiles`）中，或者内部端口只绑定在回环网卡上时，有几个默认值会放宽：

| 没有 | 开发环境中 | 生产环境中 |
| --- | --- | --- |
| `DataSource` 或 `MongoClient` | 在内存中运行，并发出警告 | 拒绝启动 |

所以笔记本上不需要数据库，而生产节点没有数据库就无法启动。`wow.ddl: validate` 防止生产节点建表（[PostgreSQL](./postgresql#manage-the-schema)）。

## 查看节点采用了什么 {#see-what-the-node-took}

应用暴露了 Spring Boot Actuator 的 `configprops` 和 `env` 端点时，它们会显示每个键绑定的值。只在管理端口上暴露它们：它们展示的是节点的配置。
