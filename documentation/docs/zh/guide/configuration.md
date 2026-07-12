---
title: 配置
description: 通过 Spring Boot 配置属性机制提供的全面配置选项。
---

# 配置

Wow 框架通过 Spring Boot 的配置属性机制提供全面的配置选项。本指南涵盖所有可用的配置选项以及如何有效地配置它们。

## 配置结构

Wow 配置在 `application.yaml` 或 `application.yml` 文件中的 `wow` 前缀下组织：

```yaml
wow:
  enabled: true                    # 启用/禁用 Wow 框架
  context-name: my-service         # 有界上下文名称
  shutdown-timeout: 60s           # 优雅关闭超时时间

  # 命令总线配置
  command:
    bus:
      type: kafka                  # kafka, redis, in_memory, no_op
      local-first:
        enabled: true              # 优先处理本地消息

  # 事件总线配置
  event:
    bus:
      type: kafka
      local-first:
        enabled: true

  # 状态事件总线配置
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
    store:
      storage: mongo               # 事件存储类型: mongo, redis, elasticsearch, in_memory, delay
    snapshot:
      enabled: true
      strategy: all                # all, version_offset
      storage: mongo
      version-offset: 10

  # 基础设施特定配置
  kafka:
    bootstrap-servers:
      - localhost:9092
    topic-prefix: 'wow.'

  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_event_db
    snapshot-database: wow_snapshot_db
    prepare-database: wow_prepare_db

  redis:
    enabled: true

  elasticsearch:
    enabled: true

  compensation:
    enabled: true
    webhook:
      weixin:
        url: <webhook-url>
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied

  openapi:
    enabled: true

  webflux:
    enabled: true
    global-error:
      enabled: true
```

## 核心配置

### WowProperties

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.enabled` | Boolean | `true` | 启用/禁用 Wow 框架 |
| `wow.context-name` | String | `${spring.application.name}` | 服务的有界上下文名称 |
| `wow.shutdown-timeout` | Duration | `60s` | 优雅关闭超时时间 |

```yaml
wow:
  enabled: true
  context-name: order-service
  shutdown-timeout: 120s
```

## 命令总线配置

### CommandProperties

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.command.bus.type` | BusType | `kafka` | 命令总线实现类型 |
| `wow.command.bus.local-first.enabled` | Boolean | `true` | 启用 LocalFirst 模式 |

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true
```

## 事件总线配置

### EventProperties

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.event.bus.type` | BusType | `kafka` | 事件总线实现类型 |
| `wow.event.bus.local-first.enabled` | Boolean | `true` | 启用 LocalFirst 模式 |

```yaml
wow:
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
```

## 状态事件总线配置

### StateProperties

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.eventsourcing.state.bus.type` | BusType | `kafka` | 状态事件总线类型 |
| `wow.eventsourcing.state.bus.local-first.enabled` | Boolean | `true` | 启用 LocalFirst 模式 |

```yaml
wow:
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
```

## 事件溯源配置

### 事件存储配置

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.eventsourcing.store.storage` | StorageType | `mongo` | 事件存储后端 |

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo    # mongo, redis, elasticsearch, in_memory, delay
```

### 快照配置

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.eventsourcing.snapshot.enabled` | Boolean | `true` | 启用快照功能 |
| `wow.eventsourcing.snapshot.strategy` | Strategy | `all` | 快照策略 |
| `wow.eventsourcing.snapshot.version-offset` | Int | `10` | VERSION_OFFSET 策略的版本偏移量 |
| `wow.eventsourcing.snapshot.storage` | StorageType | `mongo` | 快照存储后端 |

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true
      strategy: version_offset      # all, version_offset
      version-offset: 10
      storage: mongo
```

### 聚合存储路由配置

`wow.eventsourcing.storage-routing` 是可选配置。未配置某个聚合或某个通道时，Wow 会继续使用 `wow.eventsourcing.store.storage` 或 `wow.eventsourcing.snapshot.storage` 中对应的全局默认值。

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.eventsourcing.storage-routing.aggregates.*.event.storage` | StorageType | | 指定某个聚合的 EventStore 后端 |
| `wow.eventsourcing.storage-routing.aggregates.*.event.binding` | String | | 指定某个聚合的命名 EventStore binding |
| `wow.eventsourcing.storage-routing.aggregates.*.snapshot.storage` | StorageType | | 指定某个聚合的 SnapshotStore 后端 |
| `wow.eventsourcing.storage-routing.aggregates.*.snapshot.binding` | String | | 指定某个聚合的命名 SnapshotStore binding |

```yaml
wow:
  context-name: order-service
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      storage: mongo
    storage-routing:
      aggregates:
        order:
          event:
            storage: redis
        cart:
          snapshot:
            storage: redis
        audit:
          event:
            binding: archive-event-store
          snapshot:
            binding: archive-snapshot-store
```

- `order` 会使用当前 `wow.context-name` 解析为 `order-service.order`。
- 也可以直接使用 `order-service.order` 这样的完整聚合键；YAML 中必要时给 key 加引号。
- `event` 路由只影响该聚合的 `EventStore`；`snapshot` 路由只影响该聚合的 `SnapshotStore`。
- `event.binding` 与 `snapshot.binding` 指向由应用代码或基础设施自动配置注册的命名自定义 binding。
- 同一个 `event` 或 `snapshot` 通道内，`storage` 与 `binding` 互斥。
- 把路由切换到另一个后端不会迁移已有事件流或快照数据。
- 快照抽象已重命名为 `SnapshotStore`。旧的 `SnapshotRepository` Kotlin 兼容别名仅作为过渡保留，新代码不应再使用。

## 基础设施配置

### Kafka 配置

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.kafka.enabled` | Boolean | `true` | 启用 Kafka 支持 |
| `wow.kafka.bootstrap-servers` | List\<String\> | | Kafka 代理地址 |
| `wow.kafka.topic-prefix` | String | `wow.` | 主题名称前缀 |

```yaml
wow:
  kafka:
    enabled: true
    bootstrap-servers:
      - kafka-0:9092
      - kafka-1:9092
      - kafka-2:9092
    topic-prefix: 'wow.'
```

### MongoDB 配置

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.mongo.enabled` | Boolean | `true` | 启用 MongoDB 支持 |
| `wow.mongo.auto-init-schema` | Boolean | `true` | 自动创建集合 |
| `wow.mongo.event-stream-database` | String | Spring MongoDB 数据库 | 事件流数据库 |
| `wow.mongo.snapshot-database` | String | Spring MongoDB 数据库 | 快照数据库 |
| `wow.mongo.prepare-database` | String | Spring MongoDB 数据库 | 预分配键数据库 |

```yaml
wow:
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_event_db
    snapshot-database: wow_snapshot_db
    prepare-database: wow_prepare_db
```

### Redis 配置

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.redis.enabled` | Boolean | `true` | 启用 Redis 支持 |

```yaml
wow:
  redis:
    enabled: true
```


### Elasticsearch 配置

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.elasticsearch.enabled` | Boolean | `true` | 启用 Elasticsearch 支持 |

```yaml
wow:
  elasticsearch:
    enabled: true
```

## 功能配置

### 事件补偿配置

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.compensation.enabled` | Boolean | `true` | 启用事件补偿 |
| `wow.compensation.webhook.weixin.url` | String | | 企业微信 Webhook URL |
| `wow.compensation.webhook.weixin.events` | List\<String\> | 参见描述 | 通知事件 |

```yaml
wow:
  compensation:
    enabled: true
    webhook:
      weixin:
        url: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied
```

### OpenAPI 配置

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.openapi.enabled` | Boolean | `true` | 启用 OpenAPI 支持 |

```yaml
wow:
  openapi:
    enabled: true
```

### WebFlux 配置

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.webflux.enabled` | Boolean | `true` | 启用 WebFlux 支持 |
| `wow.webflux.global-error.enabled` | Boolean | `true` | 启用全局错误处理 |

```yaml
wow:
  webflux:
    enabled: true
    global-error:
      enabled: true
```

### BI 脚本配置

以下属性用于配置 `GET /wow/bi/script` 返回的 ClickHouse SQL：

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.bi.script.database` | String | `bi_db` | 状态表、命令表及展开视图所在数据库 |
| `wow.bi.script.consumer-database` | String | `bi_db_consumer` | Kafka 队列表和消费物化视图所在数据库 |
| `wow.bi.script.topology.mode` | Enum | `CLUSTER` | 物理 DDL 拓扑：`CLUSTER` 或 `STANDALONE` |
| `wow.bi.script.topology.cluster.name` | String | `{cluster}` | `CLUSTER` 模式中 `ON CLUSTER` 和 `Distributed` 使用的集群名 |
| `wow.bi.script.topology.cluster.installation` | String | `{installation}` | `CLUSTER` 模式复制表路径中的 installation 段 |
| `wow.bi.script.topology.cluster.shard` | String | `{shard}` | `CLUSTER` 模式复制表路径中的 shard 段 |
| `wow.bi.script.topology.cluster.replica` | String | `{replica}` | `CLUSTER` 模式中传给复制表引擎的副本名 |
| `wow.bi.script.timezone` | String | `Asia/Shanghai` | 生成的日期时间列和转换表达式使用的 ClickHouse 时区 |
| `wow.bi.script.kafka-bootstrap-servers` | String | 继承 `wow.kafka.bootstrap-servers`，否则为 `localhost:9093` | BI Kafka broker 覆盖值；继承多个 broker 时以逗号连接 |
| `wow.bi.script.topic-prefix` | String | 继承 `wow.kafka.topic-prefix`，否则为 `wow.` | BI topic 前缀覆盖值 |
| `wow.bi.script.max-expansion-depth` | Int | `5` | 复杂属性的最大展开深度，必须大于等于 `1` |
| `wow.bi.script.unsupported-type-strategy` | Enum | `RAW_JSON` | `RAW_JSON` 生成 scoped JSON 查询便利投影并产生诊断；精确词法值通过 `__state` 与 recovery `__path` 恢复；`FAIL` 中止生成 |

独立拓扑：

```yaml
wow:
  bi:
    script:
      topology:
        mode: STANDALONE
```

集群拓扑：

```yaml
wow:
  bi:
    script:
      topology:
        mode: CLUSTER
        cluster:
          name: production
          installation: clickhouse
          shard: '{shard}'
          replica: '{replica}'
```

`STANDALONE` 直接生成使用 `MergeTree` / `ReplacingMergeTree` 的逻辑表，并拒绝配置 `topology.cluster`。`CLUSTER` 生成复制的 `_local` 表及其 `Distributed` 逻辑表；未配置的集群字段使用上表默认值。

Kafka 与 topic 配置按以下优先级解析：

1. 显式 `wow.bi.script.kafka-bootstrap-servers` / `wow.bi.script.topic-prefix`，即使其值等于默认值；
2. 对应的 `wow.kafka.bootstrap-servers` / `wow.kafka.topic-prefix`，多个 broker 以逗号连接；
3. `BiScriptOptions` 领域默认值 `localhost:9093` / `wow.`。

其他可空绑定属性未配置时直接回退到 `BiScriptOptions` 默认值。Starter 在构造领域选项时统一执行校验：必填字符串为空白或包含控制字符、`max-expansion-depth < 1`，以及在 `STANDALONE` 模式提供集群字段都会使应用启动失败。

结构化结果诊断、当前展开语义与无损映射参见[商业智能](./bi)。

## 总线类型

框架支持多种总线实现：

| 类型 | 描述 |
|------|------|
| `kafka` | Apache Kafka 消息总线（推荐用于生产环境） |
| `redis` | Redis Streams 消息总线 |
| `in_memory` | 内存消息总线（用于测试） |
| `no_op` | 无操作消息总线（用于特殊情况） |

## 存储类型

用于事件存储和快照：

| 类型 | 描述 |
|------|------|
| `mongo` | MongoDB（推荐用于事件存储） |
| `redis` | 用于高性能场景 |
| `elasticsearch` | 用于全文搜索 |

## 完整示例

```yaml
spring:
  application:
    name: order-service

  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db
    redis:
      host: localhost
      port: 6379


  elasticsearch:
    uris:
      - http://localhost:9200

wow:
  enabled: true
  context-name: order-service
  shutdown-timeout: 120s

  command:
    bus:
      type: kafka
      local-first:
        enabled: true
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: version_offset
      version-offset: 10
      storage: mongo
  kafka:
    bootstrap-servers:
      - localhost:9092
    topic-prefix: 'wow.'
  mongo:
    enabled: true
    auto-init-schema: true
  elasticsearch:
    enabled: true
  compensation:
    enabled: true
  openapi:
    enabled: true
  webflux:
    enabled: true
    global-error:
      enabled: true

management:
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include:
          - health
          - wow
          - cosid

springdoc:
  show-actuator: true
```

## 不同环境的配置

### 开发环境

```yaml
wow:
  command:
    bus:
      type: in_memory
  event:
    bus:
      type: in_memory
  eventsourcing:
    store:
      storage: in_memory
    snapshot:
      storage: in_memory
      strategy: all
```

### 生产环境

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: version_offset
      version-offset: 10
      storage: mongo
  kafka:
    bootstrap-servers:
      - localhost:9092
  mongo:
    enabled: true
    auto-init-schema: true
```

## 配置参考

有关特定模块的详细配置，请参阅：
- [Kafka 扩展](./extensions/kafka)
- [MongoDB 扩展](./extensions/mongo)
- [Redis 扩展](./extensions/redis)
- [Elasticsearch 扩展](./extensions/elasticsearch)
- [事件补偿](./event-compensation)
- [命令配置](./reference/config/command)
- [事件配置](./reference/config/event)
- [事件溯源配置](./reference/config/eventsourcing)
