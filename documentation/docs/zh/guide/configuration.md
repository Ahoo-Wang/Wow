---
title: 配置 Wow 应用
description: 按开发、生产后端、环境与密钥边界配置 Wow；精确属性由配置参考提供。
outline: deep
---

# 配置 Wow 应用

本页帮助应用开发者做配置决策。精确属性、类型和默认值只在[配置参考](#配置参考)维护，避免指南与源码出现多份事实来源。

## 选择起点

| 场景 | Bus | EventStore / SnapshotStore | 下一步 |
| --- | --- | --- | --- |
| 首次接入、领域测试 | `in_memory` | `in_memory` | 先证明命令 → 事件 → 状态 |
| 常规生产基线 | Kafka | MongoDB | 验证持久化、重启、位点和恢复 |
| 已有 Redis 基础设施 | Redis Streams | Redis | 评估查询能力、容量和 canonical v2 布局 |
| 搜索/复杂快照查询 | Kafka/Redis | Elasticsearch 或 MongoDB | 建立索引、查询计划和重建流程 |

不要在首次运行时同时引入 Kafka、MongoDB、Redis、Elasticsearch、补偿和遥测。先完成内存垂直切片，再一次替换一个边界并保留测试证据。

## 首次运行：内存配置

```yaml
spring:
  application:
    name: order-service

cosid:
  machine:
    enabled: true
    distributor:
      type: manual
      manual:
        machine-id: 1
  generator:
    enabled: true

wow:
  prepare:
    enabled: false
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
    state:
      bus:
        type: in_memory
```

该配置只适合单进程验证：重启后数据丢失，不提供多实例投递、持久恢复或通用动态查询。`manual.machine-id` 也不能用于多实例。

## 生产起点：Kafka + MongoDB

先请求对应 Starter capabilities，再配置后端。不要只写配置键却忘记运行时依赖：

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:kafka-support") }
}
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:mongo-support") }
}
implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
```

```yaml
spring:
  application:
    name: order-service
  mongodb:
    uri: ${MONGODB_URI}

wow:
  command:
    bus:
      type: kafka
  event:
    bus:
      type: kafka
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      storage: mongo
      strategy: all
    state:
      bus:
        type: kafka
```

这只是生产配置起点。发布前还要验证认证/TLS、Topic 与 consumer group、索引、容量、备份恢复、停机、告警和滚动升级。

## 后端选择边界

### Bus

| 类型 | 适用场景 | 主要边界 |
| --- | --- | --- |
| `in_memory` | 单进程开发和测试 | 无持久化、无跨实例投递 |
| `kafka` | 多实例、可持久化消息 | 需要 Topic、分区、位点、重投与容量治理 |
| `redis` | 已使用 Redis Streams 的服务 | 需要 pending recovery、consumer group 与容量治理 |
| `no_op` | 明确不处理某类消息的特殊场景 | 消息不会产生真实业务处理 |

### Storage

| 后端 | EventStore | SnapshotStore | 动态查询 | 采用前验证 |
| --- | --- | --- | --- | --- |
| MongoDB | 是 | 是 | 事件流与快照 | 索引、分片、写关注、备份与恢复 |
| Redis | 是 | 是 | 不提供通用动态快照查询 | canonical v2、容量、持久化与 pending 恢复 |
| Elasticsearch | 是 | 是 | 事件流与快照 | Template/ILM、Bulk、PIT、重建与恢复 |
| In-memory | 是 | 是 | 仅测试用途 | 进程退出即丢失 |

同一聚合需要专属后端时使用 `wow.eventsourcing.storage-routing`，不要在业务代码中按聚合手工选择存储。精确 binding 规则见 [Spring Boot Starter](./extensions/spring-boot-starter.md#bean-装配与覆盖)。

## 配置与密钥边界

建议把配置分成三类：

| 类型 | 示例 | 存放位置 |
| --- | --- | --- |
| 可版本化策略 | Bus/Storage 类型、快照策略、超时 | 仓库内 `application.yaml` |
| 环境值 | broker 地址、数据库名、OTLP endpoint | 部署环境变量或环境配置 |
| 密钥 | 数据库密码、Token、Webhook、证书私钥 | Secret 管理系统 |

- 不在文档、示例或 ConfigMap 中写真实凭据；
- 使用 `${ENV_NAME}` 引用环境值，并在部署门禁中验证缺失值会阻止启动；
- 生产不要长期启用 `me.ahoo.wow: DEBUG`；
- 记录发布时生效配置的脱敏摘要，便于恢复与审计；
- 配置变更与应用版本一起评审，不能把“只改 YAML”视为无风险。

## 环境分层

### 开发环境

- 首选内存 Adapter 或隔离的本地后端；
- 使用单实例 manual machine-id；
- 保留 Swagger、详细日志和快速领域测试；
- 明确数据可丢失，不把本地配置复制到生产。

### 生产环境

- 使用能保证 machine-id 唯一性的分配器；
- 配置持久化 Bus、EventStore 与 SnapshotStore；
- 为命令、查询和 Actuator 配置认证与授权；
- 验证幂等索引、分区/分片、消费者位点和优雅停机；
- 完成[应用测试](./application-testing.md)与[备份、恢复和重放](./recovery.md)门禁。

## BI 脚本配置

BI 脚本服务使用 `wow.bi.script.*`，仅在实际生成或部署 ClickHouse 脚本时启用：

```yaml
wow:
  bi:
    script:
      enabled: true
      database: wow
      consumer-database: wow_consumer
      timezone: UTC
      kafka-bootstrap-servers: ${BI_KAFKA_BOOTSTRAP_SERVERS:${KAFKA_BOOTSTRAP_SERVERS}}
      topic-prefix: ${BI_TOPIC_PREFIX:wow.}
      inspector:
        type: NO_OP # 仅离线生成；部署/Reset 使用受控的 ClickHouse inspector
```

显式 `wow.bi.script.kafka-bootstrap-servers` 和 `topic-prefix` 优先于 `wow.kafka.*`。`NO_OP` inspector 适合离线生成，不应被当作已经完成 catalog 对账；执行 `RESET` 前必须使用真实 inspector 并按[BI 部署与恢复](./bi-operations.md)完成 destructive 门禁。

## 配置参考

精确属性以配置类和以下参考页为准：

- [核心配置](../reference/config/core.md)：Wow、Bus、事件溯源、快照、存储路由和 PrepareKey；
- [基础设施](../reference/config/infrastructure.md)：Kafka、MongoDB、Redis、Elasticsearch 和 WebFlux；
- [可观测性](../reference/config/observability.md)：OpenAPI、OpenTelemetry、指标和 BI；
- [事件补偿](../reference/config/compensation.md)：补偿开关、调度器和通知。

升级 Wow 时对照目标 tag 的配置类和发布说明；不要把 `main` 文档默认值套用到旧版本。
