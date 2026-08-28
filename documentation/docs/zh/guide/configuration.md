---
title: 配置 Wow 应用
description: 按 capability、运行时阶段、后端所有权和环境证据配置 Wow；精确属性由配置参考维护。
outline: deep
---

# 配置 Wow 应用

配置 Wow 不是先复制一份大 YAML，而是依次决定：运行时需要哪些 capability、三个消息通道使用什么 Bus、EventStore 与 SnapshotStore 由谁持久化，以及每个阶段由谁恢复。本页提供任务流程；精确键和默认值只在[配置参考](#配置参考)维护。

## 选择起点

| 需要证明的能力 | 最小选择 | 完成证据 |
| --- | --- | --- |
| 领域命令 → 事件 → 状态 | 基础 Starter + `in_memory` | 聚合规格和单进程重启前功能测试 |
| 跨实例命令/事件/状态投递 | `kafka-support` 或 `redis-support` | Broker 重投、积压、停机与故障注入结果 |
| 权威事件历史 | Mongo/Redis/Elasticsearch EventStore | 版本连续、并发冲突、备份与隔离恢复结果 |
| 当前状态查询 | `strategy: all` + Mongo/Elasticsearch SnapshotStore | `SNAPSHOT` 写后读、索引计划和重建结果 |
| HTTP/OpenAPI | `webflux-support`，需要独立 OpenAPI 工具时再加 `openapi-support` | 实际运行时 OpenAPI、鉴权和 route 测试 |

一次只替换一个边界。先保留内存垂直切片，再接入 EventStore，然后 Bus，再接入查询/运维入口。这样失败可以定位到具体 Wow stage，而不是同时落入多个外部系统。

## 首次运行：内存配置

只请求基础 Starter，不请求基础设施 capability，并覆盖所有指向 Kafka/Mongo 的核心默认值：

```yaml
spring:
  application:
    name: order-service

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

这只证明单进程链路。进程退出后事件和快照都丢失，没有跨实例投递、Broker 位点、持久恢复或通用动态查询。如果开发 classpath 仍保留某个 capability，还要把相应 `wow.kafka.enabled`、`wow.mongo.enabled`、`wow.redis.enabled` 或 `wow.elasticsearch.enabled` 设为 `false`，或直接删除未使用 capability。

## 生产起点：Kafka + MongoDB

以下是一个**候选拓扑**，不是生产就绪声明。基础变体与每个 capability 使用独立依赖声明；feature 已包含对应后端模块和 Spring Data starter。

```kotlin
dependencies {
    implementation(platform("me.ahoo.wow:wow-bom:<aligned-version>"))
    implementation("me.ahoo.wow:wow-spring-boot-starter")

    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.wow:kafka-support") }
    }
    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.wow:mongo-support") }
    }
    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.wow:webflux-support") }
    }
}
```

```yaml
spring:
  application:
    name: order-service
  mongodb:
    uri: ${MONGODB_URI}

wow:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
  command:
    bus:
      type: kafka
  event:
    bus:
      type: kafka
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: all
      storage: mongo
    state:
      bus:
        type: kafka
  prepare:
    storage: mongo
```

按运行时 stage 验收，不要用“应用启动了”代替：

| Stage/边界 | 应用或平台所有者必须证明 |
| --- | --- |
| 启动 | capability、配置绑定、Mongo schema/index 与 Kafka 客户端装配成功 |
| `SENT` | CommandBus 发送成功，topic/ACL/序列化可用 |
| `PROCESSED` | 聚合加载、业务决策、EventStore append 与 DomainEventBus send 完成 |
| `SNAPSHOT` | StateEventBus 消费与目标 SnapshotStore 保存/跳过策略符合预期 |
| `PROJECTED` / `EVENT_HANDLED` / `SAGA_HANDLED` | 精确目标函数完成，且重投不会重复外部副作用 |
| 停机 | 入口摘流后在 `wow.shutdown-timeout` 内静默并排空已准入工作 |
| 恢复 | EventStore、快照、投影、Broker 位点和补偿状态通过隔离恢复与对账 |

## 后端选择边界

### Bus

| 类型 | 能力 | 不提供 |
| --- | --- | --- |
| `in_memory` | 单实例快速验证 | 持久性、跨实例投递 |
| `kafka` | Kafka 分布式 Bus | topic/ACL/retention/offset 备份的自动治理 |
| `redis` | Redis Streams 分布式 Bus 与 pending recovery | 已裁剪 Stream 的恢复、EventStore 备份 |
| `no_op` | 明确关闭某类处理 | 业务执行或可恢复投递 |

命令、领域事件、状态事件可以分别选择 Bus。三者的 owner 与恢复策略也必须分别记录；只验证 CommandBus 不能证明投影或快照链路。

### Storage

| 后端 | EventStore | SnapshotStore | 动态查询 | 采用前必须证明 |
| --- | --- | --- | --- | --- |
| MongoDB | 是 | 是 | 事件与快照 | schema/index、写关注、备份、恢复、查询计划 |
| Redis | 是 | 是 | 无通用实现 | canonical key 布局、持久化、容量、重启恢复 |
| Elasticsearch | 是 | 是 | 事件与快照 | template、Bulk、PIT、cluster snapshot 与重建 |
| In-memory | 是 | 是 | 不用于生产 | 数据可丢失边界 |
| Delay | 测试 | 测试 | 否 | 只来自 `mock-support`，不得用于生产 |

按聚合分流时使用 `wow.eventsourcing.storage-routing`。每个 route 的 `event` 与 `snapshot` 独立；回滚、备份与查询 factory 也必须覆盖实际 binding，而不是只覆盖默认存储。

## 配置与密钥边界

| 所有权 | 示例 | 建议载体 |
| --- | --- | --- |
| 应用合同 | Bus/Storage 类型、快照策略、HTTP query guard | 版本库内配置，与代码一起评审 |
| 环境拓扑 | Broker/数据库 endpoint、database/topic prefix | 部署配置或环境变量 |
| 密钥 | 用户名、密码、Token、私钥 | Secret 管理系统 |
| 运维基线 | 生效配置摘要、topic/index/template、备份点 | 发布证据库，必须脱敏 |

使用 `${ENV_NAME}` 引用外部值，并在候选环境验证缺失值会阻止启动。不要在示例、ConfigMap、日志或 Issue 中保存真实 URI 凭据。配置变更会改变消息、存储或恢复边界，必须与应用版本一起评审。

## 环境分层

### 开发环境

1. 先用内存配置跑聚合规格与一个完整命令链路。
2. 只引入当前要验证的 capability，并显式关闭 classpath 上未使用的集成。
3. 对外部后端使用隔离 namespace；不得复用生产 topic、database、consumer group 或凭据。
4. 保存失败测试、实际配置和后端健康证据，随后再替换下一个边界。

### 生产环境

生产候选必须把配置映射到 Wow stage：Bus owner 负责投递与位点，EventStore owner 负责权威历史，Snapshot/Projection owner 负责派生状态，应用 owner 负责 Handler 幂等、HTTP 鉴权与停机。发布证据至少来自生产同构环境的失败路径、备份恢复、重投/对账、滚动停机和容量测试；模块 checks 或一份 YAML 不能证明生产就绪。

详见[生产最佳实践](./best-practices.md)、[备份、恢复与重放](./recovery.md)和[故障排查](./troubleshooting.md)。

## BI 脚本配置

只有实际生成或部署 ClickHouse 脚本时才启用 `wow.bi.script.*`。离线生成可使用 `NO_OP` inspector，但这不证明 ClickHouse catalog 已对账；`RESET` 必须使用受控 inspector 并完成 destructive 门禁。

```yaml
wow:
  bi:
    script:
      enabled: true
      database: ${BI_DATABASE}
      consumer-database: ${BI_CONSUMER_DATABASE}
      kafka-bootstrap-servers: ${BI_KAFKA_BOOTSTRAP_SERVERS}
      topic-prefix: ${BI_TOPIC_PREFIX}
      inspector:
        type: NO_OP # 仅离线生成
```

精确 BI 属性与操作流程见[可观测性配置](../reference/config/observability.md)和[BI 部署与恢复](./bi-operations.md)。

## 配置参考

- [核心配置](../reference/config/core.md)：运行时、Bus、EventStore、Snapshot、storage routing、query schema 与 PrepareKey。
- [基础设施配置](../reference/config/infrastructure.md)：Kafka、MongoDB、Redis、Elasticsearch 与 WebFlux。
- [可观测性配置](../reference/config/observability.md)：OpenAPI、OpenTelemetry、指标与 BI。
- [事件补偿配置](../reference/config/compensation.md)：补偿开关、调度与通知。

升级时以目标发布版本的配置元数据与配置类为准；不要把 `main` 的键或默认值套用到旧版本。
