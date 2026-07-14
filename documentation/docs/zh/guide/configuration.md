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

以下属性用于建立 `POST /wow/bi/script` 返回的 ClickHouse SQL 的服务端基础配置：

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `wow.bi.script.enabled` | Boolean | `true` | 默认启用；配置为 `false` 时同时移除 BI 脚本 HTTP 路由及其 OpenAPI operation；端点仍须由应用安全策略保护 |
| `wow.bi.script.database` | String | `bi_db` | command/state `*_store` 存储表、公共视图、最新状态视图及展开视图所在数据库；最大 128 个字符 |
| `wow.bi.script.consumer-database` | String | `bi_db_consumer` | Kafka 队列表、消费物化视图及 deployment anchor 所在数据库；最大 128 个字符 |
| `wow.bi.script.topology.mode` | Enum | `CLUSTER` | 物理 DDL 拓扑：`CLUSTER` 或 `STANDALONE` |
| `wow.bi.script.topology.cluster.name` | String | `{cluster}` | `CLUSTER` 模式中 `ON CLUSTER` 和 `Distributed` 使用的集群名；最大 128 个字符 |
| `wow.bi.script.topology.cluster.installation` | String | `{installation}` | `CLUSTER` 模式复制表路径中的 installation 段；最大 128 个字符 |
| `wow.bi.script.timezone` | String | `Asia/Shanghai` | 生成的日期时间列和转换表达式使用的 ClickHouse 时区；最大 64 个字符 |
| `wow.bi.script.kafka-bootstrap-servers` | String | 继承 `wow.kafka.bootstrap-servers`，否则为 `localhost:9093` | BI Kafka broker 覆盖值；继承多个 broker 时以逗号连接；最大 4096 个字符 |
| `wow.bi.script.topic-prefix` | String | 继承 `wow.kafka.topic-prefix`，否则为 `wow.` | BI topic 前缀覆盖值；最大 128 个字符 |
| `wow.bi.script.consumer-group-namespace` | String | 无 | 所有 `RESET`（包括空聚合作用域）均必填；`DEPLOY` 生成 Kafka consumer 时也必填；写入每个 consumer group 的部署唯一命名空间 |
| `wow.bi.script.kafka-offset-storage` | Enum | `BROKER` | `BROKER` 使用 Kafka offset；`KEEPER` 使用 ClickHouse Keeper offset |
| `wow.bi.script.kafka-keeper-path-prefix` | String | `/clickhouse/wow-bi` | 仅 `KEEPER` 模式使用的 Keeper 路径前缀 |
| `wow.bi.script.max-expansion-depth` | Int | `5` | 复杂属性的最大展开深度，必须大于等于 `1` |
| `wow.bi.script.unsupported-type-strategy` | Enum | `RAW_JSON` | `RAW_JSON` 生成 scoped JSON 查询便利投影并产生诊断；精确词法值通过 `__state` 与 recovery `__path` 恢复；`FAIL` 中止生成 |
| `wow.bi.script.inspector.type` | Enum | `NO_OP` | 部署状态检查实现：`NO_OP` 或 `CLICKHOUSE`；只有显式选择 `CLICKHOUSE` 才连接 catalog |
| `wow.bi.script.inspector.timeout` | Duration | `30s` | 完整 inspection 的总 deadline；集群检查包含两次 catalog 操作，因此应大于单次 operation execution timeout |
| `wow.bi.script.inspector.clickhouse.endpoints` | List&lt;URI&gt; | 无 | 一个或多个互不重复的 ClickHouse HTTP(S) endpoint；必须显式包含端口，可包含反向代理 base path |
| `wow.bi.script.inspector.clickhouse.username` | String | `default` | ClickHouse Basic Auth 用户名 |
| `wow.bi.script.inspector.clickhouse.password` | String | 空 | ClickHouse Basic Auth 密码；属性和客户端选项的字符串表示都会脱敏 |
| `wow.bi.script.inspector.clickhouse.connection-pool-enabled` | Boolean | `true` | 对应 `Client.Builder.enableConnectionPool` |
| `wow.bi.script.inspector.clickhouse.connection-timeout` | Duration | `3s` | 对应 `Client.Builder.setConnectTimeout`，最小为 `1ms` |
| `wow.bi.script.inspector.clickhouse.connection-request-timeout` | Duration | `10s` | 等待连接池连接的最长时间；对应 `setConnectionRequestTimeout`，最小为 `1ms` |
| `wow.bi.script.inspector.clickhouse.socket-timeout` | Duration | `10s` | socket 读写超时；对应 `setSocketTimeout`；最小为 `1ms`，且不得大于 `inspector.timeout` |
| `wow.bi.script.inspector.clickhouse.execution-timeout` | Duration | `10s` | 单次驱动 operation deadline；检查器启用异步请求，同时配置 `setExecutionTimeout` 并使用该值限制返回 future 的等待时间；零表示不设置驱动 operation deadline，非零值最小为 `1ms` |
| `wow.bi.script.inspector.clickhouse.max-connections` | Int | `10` | 每个 endpoint 的最大连接数；对应 `setMaxConnections`，必须大于零 |
| `wow.bi.script.inspector.clickhouse.max-retries` | Int | `0` | 驱动重试次数；对应 `setMaxRetries`，不得为负数 |

`execution-timeout` 限制检查器等待异步结果的时间，但 client-v2 不会把 future 超时转换为 HTTP abort。因此 `socket-timeout` 必须配置且不得大于总 `inspector.timeout`；取消后的响应清理会移出 timeout scheduler，并继续受该传输层 deadline 约束。

默认 `NO_OP` 不访问 ClickHouse。需要根据实际 catalog ownership marker 对账时，选择 `CLICKHOUSE` inspector：

```yaml
wow:
  bi:
    script:
      enabled: true
      consumer-group-namespace: orders-production-blue
      topology:
        mode: STANDALONE
      inspector:
        type: CLICKHOUSE
        timeout: 30s
        clickhouse:
          endpoints:
            - http://clickhouse-1:8123
            - http://clickhouse-2:8123
          username: default
          password: ${CLICKHOUSE_PASSWORD:}
          connection-pool-enabled: true
          connection-timeout: 3s
          connection-request-timeout: 10s
          socket-timeout: 10s
          execution-timeout: 10s
          max-connections: 10
          max-retries: 0
```

内置 inspector 位于 `wow-bi`，直接使用 ClickHouse 官方 Java `client-v2`。Spring Boot 强类型属性与对应的 `Client.Builder` 概念一一映射，不再把不同语义的驱动超时合并成一个字段。inspector 独占 Client 并随 Spring Context 关闭；它发起 client-v2 异步查询，并在 Reactor `boundedElastic` 上等待每个返回的 future，不额外创建驱动 executor。catalog 查询使用带命名参数的强类型 RowBinary 记录；集群模式核验参与副本集合与 owned 对象定义，同时忽略无关的副本本地 catalog 差异。连接错误、超时、无效 ownership marker 或 owned 对象副本不一致都会直接失败，不会静默回退到 `NO_OP`。选择 `CLICKHOUSE` 但缺少 client-v2 类时应用启动失败。官方客户端属性尚未覆盖的代理、mTLS 或认证需求可通过自定义 `BiDeploymentInspector` Bean 实现；自定义 Bean 优先于两种内置实现。

独立拓扑：

```yaml
wow:
  bi:
    script:
      enabled: true
      consumer-group-namespace: orders-production-blue
      topology:
        mode: STANDALONE
```

集群拓扑：

```yaml
wow:
  bi:
    script:
      enabled: true
      consumer-group-namespace: orders-production-blue
      topology:
        mode: CLUSTER
        cluster:
          name: production
          installation: clickhouse
```

`STANDALONE` 创建使用 `ReplacingMergeTree` 的 `*_store` 物理存储表，`command`、`state`、`state_last` 保持为对存储表执行 `FINAL` 的只读视图，并拒绝配置 `topology.cluster`。`CLUSTER` 创建复制的 `*_store_local` 物理表、作为 `Distributed` 写入门面的 `*_store`，以及相同的公共只读视图；未配置的集群字段使用上表默认值。集群 DDL 始终使用 ClickHouse 服务端 `{shard}` 与 `{replica}` 宏，Keeper consumer 的副本标识也使用 `{replica}`；这些值有意不开放为应用级覆盖项。

`DEPLOY` 与 `RESET` 只对账当前物理归属范围，不迁移 `database`、`consumerDatabase`、`consumerGroupNamespace`、拓扑模式、cluster name 或 installation。可见的 topology fingerprint 漂移会被拒绝；范围变化可能使旧对象不可发现，因此必须先停止旧 consumer、显式清理旧范围，再部署新范围。

完整优先级从低到高为：

1. `BiScriptOptions` 领域默认值；
2. Kafka 的 bootstrap servers 与 topic prefix 配置；
3. `wow.bi.script.*` 应用配置；
4. 非 `null` 的 `POST` 请求字段。

配置真实 deployment inspector 后，`database`、`consumerDatabase` 与 `topology` 固定使用服务端配置；请求覆盖这些字段会返回 `400`，避免外部请求借用服务端 ClickHouse 凭据查询任意数据库或集群。默认 `NO_OP` inspector 不访问 ClickHouse，因此仍允许这些覆盖；其输出仅是离线预览，不会迁移已有范围。

因此，显式 `wow.bi.script.kafka-bootstrap-servers` / `wow.bi.script.topic-prefix` 会覆盖对应的 `wow.kafka.bootstrap-servers` / `wow.kafka.topic-prefix`，即使其值等于默认值；继承多个 Kafka broker 时以逗号连接。其他未配置的应用绑定属性直接回退到 `BiScriptOptions` 领域默认值。表中的长度限制同时适用于服务端配置和对应的非 `null` `POST` override（`database`、`consumerDatabase`、`timezone`、`kafkaBootstrapServers`、`topicPrefix`、`topology.cluster.name` 和 `topology.cluster.installation`）。长度恰好等于 64、128 或 4096 字符限制的值可被接受。启用 BI 脚本生成时，Starter 在构造服务端基础选项时统一执行校验：超过长度限制、必填字符串为空白或包含控制字符、`max-expansion-depth < 1`，以及在 `STANDALONE` 模式提供集群字段都会使应用启动失败。配置 `enabled=false` 时，Starter 不构造或校验 BI 生成选项和 inspector。对于 HTTP override，服务端配置的 `maxExpansionDepth` 是请求 ceiling。

端点及其 OpenAPI operation 默认注册；配置 `enabled=false` 会同时移除二者。启用本身不会提供鉴权。未配置 `consumer-group-namespace` 不会导致启动失败，但所有 `RESET`（包括空聚合作用域），以及会生成 Kafka consumer 的 `DEPLOY` 都会返回 `400`；未配置时的空 `DEPLOY` 不建立 anchor。端点要求 `Content-Type: application/json` 和 JSON 请求体。使用 `{}` 可在不提供请求覆盖值的情况下按服务端基础配置生成 SQL：

```bash
curl -X POST 'http://localhost:8080/wow/bi/script' \
  -H 'content-type: application/json' \
  -H 'accept: application/sql' \
  --data '{}'
```

非 `null` 请求字段会在本次生成中同时覆盖普通选项和从 Kafka 继承的选项。独立模式请求还可以覆盖数据库：

```json
{
  "database": "analytics",
  "topology": {
    "mode": "STANDALONE"
  }
}
```

集群模式请求可以只提供部分集群字段。省略的集群字段继承当前集群服务端基础配置；如果服务端基础配置是独立模式，则继承 `BiScriptOptions` 的集群默认值：

```json
{
  "topology": {
    "mode": "CLUSTER",
    "cluster": {
      "name": "production"
    }
  },
  "kafkaBootstrapServers": "kafka:9092",
  "topicPrefix": "analytics."
}
```

提供 `topology` 时必须提供 `topology.mode`。`STANDALONE` 拒绝 `cluster` 对象。无效 JSON、空请求体、超过长度限制的非 `null` override、其他无效选项值或无效拓扑组合返回 `400`；缺少或不支持的请求 `Content-Type` 返回 `415`。OpenAPI 声明公共 `wow.UnsupportedMediaType` response，运行时使用 `Wow-Error-Code: UnsupportedMediaType`。未预期的生成失败返回 `500`。启用真实 inspector 后，catalog 状态不一致返回 `502`，ClickHouse 不可用返回 `503`，检查超时返回 `504`。响应会遵循 `Accept` 的 quality value；JSON 返回 SQL、诊断与 destructive 标记，SQL 与通配符返回 SQL。如果请求未接受任何受支持的表示，或将其全部显式设为 `q=0`，端点返回 `406` 及 `Wow-Error-Code: NotAcceptable`。所有 `200` 响应都包含 `Wow-BI-Diagnostic-Count`，包括响应体无法携带诊断的 SQL 响应。调用方不再提交 manifest。默认 NoOp inspector 允许离线 `DEPLOY` 并返回未对账诊断，但拒绝 `RESET`；显式配置 ClickHouse inspector 后，服务从 catalog ownership marker 恢复 identity、清理旧对象，并在 `replayFromEarliestConfirmed=true` 时执行 Reset。

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
