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
      storage: mongo               # 事件存储类型: mongo, r2dbc, redis, elasticsearch
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

  r2dbc:
    enabled: true
    datasource:
      type: simple                 # simple 或 sharding

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
      storage: mongo    # mongo, r2dbc, redis, elasticsearch
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

### R2DBC 配置

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.r2dbc.enabled` | Boolean | `true` | 启用 R2DBC 支持 |
| `wow.r2dbc.datasource.type` | Type | `simple` | simple 或 sharding |

```yaml
wow:
  r2dbc:
    enabled: true
    datasource:
      type: simple
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
| `r2dbc` | R2DBC 兼容数据库 |
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

  r2dbc:
    url: r2dbc:pool:mysql://localhost:3306/wow_db

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
  r2dbc:
    enabled: true
    datasource:
      type: simple
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
- [R2DBC 扩展](./extensions/r2bdc)
- [事件补偿](./event-compensation)
- [命令配置](./reference/config/command)
- [事件配置](./reference/config/event)
- [事件溯源配置](./reference/config/eventsourcing)
