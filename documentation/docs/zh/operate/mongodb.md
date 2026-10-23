---
title: MongoDB
description: 在 MongoDB 上运行服务，它需要副本集；Wow 创建的集合、追加和读关注、查询的读偏好，以及 schema 管理。
outline: deep
---

# MongoDB

在 MongoDB 上，一个数据库保存服务存储的一切：它的日志、快照、定时器，以及它的检查点、租约、失败台账和预留。追加和读取都经过多数派，所以 MongoDB 必须以副本集运行。本页连接一个服务，并说明它依赖什么。

## 连接服务 {#connect-a-service}

把 `wow-mongo` 放到类路径上，并给应用一个同步驱动的 `MongoClient` Bean：宿主随后选择 MongoDB。Wow 的基准测试服务在它的 `mongo` profile 中定义了一个：

<!-- sample: test/benchmarks/server/src/main/kotlin/me/ahoo/wow/benchmark/transfer/TransferServer.kt#mongo -->

```kotlin
/**
 * The benchmark's V10 server: the account and the transfer saga on MongoDB (the `mongo` profile) or PostgreSQL (the
 * `postgres` profile), its state stream published to Kafka (`application.yaml`). A node of a cluster takes its ports
 * and node id from the command line (test/benchmarks/README.md).
 */
@SpringBootApplication(proxyBeanMethods = false)
class TransferServer

/**
 * The `mongo` profile: the application's `MongoClient` on `spring.mongodb.uri`, which selects the MongoDB backend. It
 * takes the application's driver [CommandListener]s: none in the server, the round-trip counter in ComponentBench.
 */
@Configuration(proxyBeanMethods = false)
@Profile("mongo")
class MongoBackend {
    @Bean(destroyMethod = "close")
    fun mongoClient(environment: Environment, listeners: ObjectProvider<CommandListener>): MongoClient =
        MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(environment.getRequiredProperty("spring.mongodb.uri")))
                .apply { listeners.orderedStream().forEach(::addCommandListener) }
                .build(),
        )
}
```

连接字符串要指明副本集，例如 `mongodb://mongo-1,mongo-2,mongo-3/?replicaSet=rs0`。数据写入数据库 `wow.mongo.database`，默认是 `wow`；给每个服务一个自己的数据库。

## 必须使用副本集 {#a-replica-set-is-required}

- **多数派。** 追加由多数派确认（`w: majority`），每次读取，包括订阅的尾随读，都只读取多数派已提交的内容（`readConcern: majority`）。所以订阅永远不会对一次之后因故障切换而回滚的提交采取行动。代价是条目到达尾随读之前，大约要多一次复制往返。多数派无法提交的副本集（例如带仲裁节点且有一个成员宕机），会让尾随读停滞，直到能够提交为止。单机服务器没有多数派；即使是一个开发节点，也要以单成员副本集运行。
- **追加。** 一次追加就是一次插入，不开事务。节点从分区的计数器一次取一段 256 个序号，依次分给它的追加；唯一键拒绝已被占用的版本或请求。节点取了却没有写入的序号会写上占位，尾随读越过它们，所以分区的序号递增、但有空号。同一分区的追加之间没有争用，也不需要队列。

`wow.mongo.append-timeout`（默认 5 秒）限制一次追加的插入时长，它低于默认的等待超时，所以在故障期间追加的命令会在它的等待结束之前得到回答。

## 集合 {#the-collections}

存储在第一次使用时创建它们的集合和索引，命名方式与 PostgreSQL 相同：

| 集合 | 保存 |
| --- | --- |
| `wow_log__<context>__<aggregate>` | 一个聚合类型的日志，每条已提交的流一个文档，以及没有流占用的序号的占位 |
| `wow_log__<context>__partition` | 上下文日志的每个分区一个计数器文档：已分出的序号，以及尚未封口的段 |
| `wow_log_aggregate` | 每个上下文的日志有哪些聚合集合 |
| `wow_snapshot__<context>__<aggregate>` | 每个聚合一个文档：它最新的快照 |
| `wow_snapshot_table` | 每个快照集合保存的状态修订号 |
| `wow_timer__<context>` | 上下文的定时命令 |
| `wow_checkpoint`、`wow_lease`、`wow_lease_member` | 每个订阅进行到哪里，以及哪个节点拥有哪个分区 |
| `wow_failure_ledger`、`wow_reservation` | 停放的聚合，以及预留的唯一键 |
| `wow_format` | 每个结构的格式版本 |

新聚合类型的集合在日志第一次用到它时创建；不需要重启。小数以 `Decimal128` 精确存储；载荷中有 MongoDB 无法精确存储的数字时，追加会被拒绝。

与 PostgreSQL 一样，`wow.ddl` 决定存储是创建它们的集合和索引（`auto`，默认）、只检查它们（`validate`），还是两者都不做（`none`）（[PostgreSQL](./postgresql#manage-the-schema)）。

## 查询与读偏好 {#queries-and-read-preferences}

快照查询和 `events` 查询默认读取主节点，与追加在一起。在大集合上的自由过滤可能会扫描；用读偏好把这类查询发到从节点：

| 键 | 适用于 |
| --- | --- |
| `wow.query.events.mongo-read-preference` | 每个 `events` 查询，例如 `secondaryPreferred` |
| `wow.stores.<name>.query.read-preference` | 一个存储的快照查询和 `events` 查询 |

从节点可能落后于主节点；必须看到某次写入的读取，要改为指定它的版本（[配置项](../reference/configuration#wow-query)）。

## 多个数据库 {#several-databases}

与 PostgreSQL 一样，命名的存储实例把各聚合类型放到不同的数据库或集群上：`wow.stores.<name>.type: mongo`，并指定每个实例使用的 `client` Bean 和 `database`（[PostgreSQL](./postgresql#several-databases)）。两个日志存储不能共用一个数据库。

## 备份 {#backups}

日志是唯一的事实；快照、读模型和数仓都从它重建。把日志集合与检查点、租约、台账、预留和定时器一起，作为数据库的一次一致备份来备份。
