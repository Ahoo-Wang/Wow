---
title: 部署 V10 服务
description: Wow V10 服务的后端、内部端口、多节点、Kafka 主题、ClickHouse、停机、事件中的个人信息与共用的租户空间。
outline: deep
---

# 部署 V10 服务

## 选择后端

节点把日志、快照、检查点、租约、失败台账、预留键与定时器放在同一个后端里，由 `wow.backend` 选择：

| 后端 | 模块 | 应用提供 | 节点数 |
| --- | --- | --- | --- |
| `memory` | 无 | 无 | 一个；数据不会比节点活得更久，宿主启动时给出警告 |
| `postgres` | `wow-postgres`（自带 PostgreSQL 驱动） | 一个 `javax.sql.DataSource` Bean | 共用数据库的任意多个 |
| `mongo` | `wow-mongo`（自带同步驱动） | 一个 `com.mongodb.client.MongoClient` Bean；`wow.mongo.database`（`wow`） | 共用数据库的任意多个；MongoDB 必须是副本集，因为追加是事务 |

默认的 `auto` 在有 PostgreSQL 模块与 `DataSource` 时选 PostgreSQL，否则选 MongoDB，否则选内存。应用自己的 `BackendFactory` Bean 会取代这个选择。

PostgreSQL 的 `DataSource` Bean，按示例 `postgres` profile 的写法：

```kotlin
@Configuration(proxyBeanMethods = false)
class PostgresDataSource {
    @Bean(destroyMethod = "close")
    fun dataSource(environment: Environment): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = environment.getRequiredProperty("spring.datasource.url")
            username = environment.getProperty("spring.datasource.username")
            password = environment.getProperty("spring.datasource.password")
        },
    )
}
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/catalog
    username: postgres
    password: postgres
wow:
  backend: postgres
```

宿主在启动时创建它的表与集合。日志与快照按聚合类型存储（`wow_log__<context>__<aggregate>`、`wow_snapshot__<context>__<aggregate>`）；定时器按上下文存储；检查点、租约、台账与预留键共用。`wow.log.partitions`（64）在上下文的日志有条目后即固定：首次部署之前定好。

## 运行多个节点

在 PostgreSQL 或 MongoDB 上，同一服务的多个节点分担工作：上下文日志的每个分区同一时刻只有一个属主，由数据库中的租约决定（不需要 ZooKeeper，也不需要消费组）。任一节点收到的命令经内部端口转发给其分区的属主；属主不可达时，收到命令的节点自己执行，版本检查保证结果正确。给每个节点设置自己的 `server.port`（共用主机时）、`wow.internal.port` 与 `wow.node.id`。

## 内部端口

每个节点另起一个 HTTP/2（h2c）端口 `wow.internal.port`（9090），承载集群通道（转发的命令、新的日志条目）与等待信号：执行命令的节点、或处理 Saga 命令的上下文，在这个端口上通知等待开始的那个节点。

- **它不做认证**（D173）。服务必须运行在内网中，内部端口绝不能暴露到内网之外：不要让公网负载均衡、Ingress 或端口映射指向它。
- **节点之间必须能通过它互相访问**，跨上下文等待的其他服务的节点也必须能访问：跟随命令进入另一个上下文的等待，由那个上下文的节点发出信号。只在服务的节点之间、以及共享等待的服务之间放开这个端口。
- **把它绑定到内网网卡**：除非 `wow.internal.host` 指定，该端口绑定所有网卡。把 `wow.internal.host` 设为节点的内网地址；其他节点使用的地址不同时（NAT、容器内）再设置 `wow.node.address`。
- 同一服务的所有节点（滚动升级期间也是）必须运行同一主版本中相邻的次版本：内部协议只在这个范围内兼容。

## Kafka

有 `wow-kafka` 并设置 `wow.kafka.bootstrap-servers` 后，节点使用下列主题（都加上 `wow.kafka.topic-prefix`）：

| 主题 | 写入方 | 读取方 |
| --- | --- | --- |
| `wow.<context>.<aggregate>.state` | 本上下文的 Sink，每个发布的聚合每次提交一条记录 | 其他上下文中读取该流的处理函数 |
| `wow.<context>.commands` | 其他上下文的 Saga 与定时器 | 本上下文，需设置 `wow.kafka.command-intake: true` |
| `wow.<context>.receipts` | 其他上下文，针对它们拒绝的本上下文命令 | 本上下文的 `@OnRejected` 处理函数 |

节点不创建主题。首次启动之前创建它们；命令主题的分区数必须恰好等于 `wow.log.partitions`（默认 64），否则开启 `command-intake` 的节点无法启动：

```bash
kafka-topics --bootstrap-server localhost:9092 --create --topic wow.inventory.commands --partitions 64
```

读取其他上下文流的订阅把偏移量记在本服务的检查点里，分区由本服务的租约分配：没有需要管理的消费组。`wow.kafka.properties.*` 把 `security.protocol` 等客户端设置传给每个生产者与消费者。

## ClickHouse

有 `wow-clickhouse` 并设置 `wow.clickhouse.endpoint` 后，Sink 把每条发布的流写入 ClickHouse，数据集从那里提供。`wow.clickhouse.sink.ddl: auto` 创建表与视图；由运维管理表结构时用 `validate`。按聚合设置 `history.enabled: false` 只保留当前状态，`history.ttl` 限定每次提交的状态保留多久。

## 停机与健康

正在停止的节点不再接收新命令（`wow.node.closing`），在其在途命令完成后交出分区（`wow.cluster.drain-timeout`），让进行中的等待结束（`wow.wait.max-timeout`），并保存检查点。除非设置了 `spring.lifecycle.timeout-per-shutdown-phase`，宿主把 Spring Boot 的停机阶段时限设为两者之和；平台的终止宽限期至少要这么长。

## 事件中的个人信息

事件是事实，永不修改：日志永久保存它们，V10 既不提供事件擦除，也不提供加密粉碎（D175）。在建模时就要考虑：

- 尽量不把个人信息（姓名、地址、电话、关于某人的自由文本）放进事件。把它放进可以删除的读模型（投影的表、另一个服务），事件里只放不透明的 ID。
- 命令需要个人信息来做决定时，让命令函数通过服务读取它，而不是把它带进事件。
- 命令的操作人只记录不透明的 ID。复制进事件的请求事实（`wow.http.request-facts`）也不能是个人信息；IP 地址与 User-Agent 不记录。
- 快照与 ClickHouse 的表都由日志派生：在那里删除一行并不会删除事实。

## 一个租户空间

一个系统中的所有上下文共用一个租户空间（D180）。租户 ID 原样跨服务传递：在 Saga 的命令里（`AggregateType(...).ref(event.ref.tenantId, id)`）、在发布的流里、在回执与定时器里。上下文之间没有租户映射，所以每个服务要对同一个客户使用相同的租户 ID，并在每个服务的 `CallerResolver` 中以相同的方式解析调用方的租户。为某个聚合类型固定租户（`wow.aggregates.[<context>.<aggregate>].tenant`）会让它的命令与状态读取都落在该租户，与调用方无关。
