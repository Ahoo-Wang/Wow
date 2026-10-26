---
title: PostgreSQL
description: "在 PostgreSQL 上运行服务：DataSource、Wow 创建的表、用于追加和查询的连接池、把聚合放在多个数据库上，以及 schema 管理。"
outline: deep
---

# PostgreSQL

在 PostgreSQL 上，一个数据库保存服务存储的一切：它的日志、快照、定时器，以及它的检查点、租约、失败台账和预留。日志每次提交一条自动提交的语句；负载高时，节点把一个分区的追加合成一条语句。本页连接一个服务、介绍这些表，并确定连接池的大小。

## 连接服务 {#connect-a-service}

把 `wow-postgres` 和 HikariCP 放到类路径上，并设置 `spring.datasource.url` 以及 `spring.datasource.username`、`spring.datasource.password`：宿主用它们建一个带连接池的 `DataSource`，并选择 PostgreSQL。HikariCP 自己的设置放在 `spring.datasource.hikari.*` 下，例如 `maximum-pool-size`。连接池在第一次取连接时才打开。没有设置 URL 时，宿主不连接任何数据库。

自己定义了 `DataSource` Bean 的应用保留它；引入了 Spring Boot JDBC 模块（`spring-boot-starter-jdbc`）的应用，使用 Spring Boot 用同样的属性建的那个。eShop 的服务只在它们的 `postgres` profile 中设置这些属性：

<!-- sample: example/eshop/ordering/ordering-server/src/main/resources/application.yaml#postgres -->

```yaml
spring:
  config:
    activate:
      on-profile: postgres
  datasource:
    url: jdbc:postgresql://localhost:5432/eshop_ordering
    username: postgres
    password: postgres
wow:
  backend: postgres
```

`wow.backend: postgres` 直接指明后端，而不是交给应用拥有的 Bean 来决定。每个服务有自己的数据库；两个服务从不共用一个。

## 表 {#the-tables}

存储在第一次使用时创建它们的表。表名由上下文和聚合类型推出，所以人或查询服务可以直接读取一个聚合的数据：

| 表 | 保存 |
| --- | --- |
| `wow_log__<context>__<aggregate>` | 一个聚合类型的日志：每条已提交的流一行，它的事实放在列里（版本、命令、请求、作用域、因果、操作人，标签为 `jsonb`），以及它的事件 |
| `wow_log__<context>__partition` | 上下文日志的每个分区一行计数器 |
| `wow_log_aggregate` | 每个上下文的日志有哪些聚合表 |
| `wow_snapshot__<context>__<aggregate>` | 每个聚合一行：它最新的快照，状态和标签为 `jsonb`，直接在原处查询 |
| `wow_snapshot_table` | 每张快照表保存的状态修订号 |
| `wow_timer__<context>` | 上下文的定时命令 |
| `wow_checkpoint`、`wow_lease`、`wow_lease_member` | 每个订阅进行到哪里，以及哪个节点拥有哪个分区 |
| `wow_failure_ledger` | 停放的聚合 |
| `wow_reservation` | 预留的唯一键 |
| `wow_format` | 每个结构的格式版本 |

日志表的键是位置（`partition`、`sequence`）、版本（`aggregate_id`、`tenant_id`、`version`）和请求（`tenant_id`、`request_id`）：数据库本身就会拒绝同一版本或同一请求的第二次提交。

**格式。** 每个结构在创建之前先在 `wow_format` 中声明它的格式版本。以另一个版本记录的结构，或者存在却没有记录的结构，都会被拒绝：一个 Wow 构建从不读写它不认识的布局。同一格式内新增的列，会在下次检查时加到已有的表上。

## 管理 schema {#manage-the-schema}

`wow.ddl` 决定存储怎样对待它们的表：

| `wow.ddl` | 作用 |
| --- | --- |
| `auto`（默认） | 创建缺失的东西 |
| `validate` | 什么都不创建；缺少表或表是另一种格式时拒绝启动 |
| `none` | 什么都不创建，也不检查 |

数据库账户不能建表的部署，先用一个能建表的账户运行一次，或者自己应用 schema，然后以 `validate` 运行。`wow.stores.<name>.ddl` 为单个存储设置它。

## 确定连接池的大小 {#size-the-pools}

每个节点为它的追加、订阅、定时器和租约持有几个连接，为它的查询持有更多。连接池耗尽时追加就会等待，所以给查询一个单独的连接池：

- 定义第二个 `DataSource` Bean，并把它指定为存储的 `query-data-source`：快照查询和 `events` 查询就从它取连接，绝不会占用追加正在等待的连接。它可以指向只读副本。
- 没有单独的连接池时，查询与追加共用连接池，节点把查询限制在它的一半。追加变慢或等待连接时，节点会进一步降低这个上限，恢复后再提高（[配置项](../reference/configuration#wow-query-adaptive)）。

按数据库计算连接数：连接池大小乘以服务的节点数，加上迁移和运维人员使用的连接，要在 PostgreSQL 的 `max_connections` 之内。

存储借出的每个连接等待回应最多 `wow.postgres.operation-timeout`（默认 30 秒，单个存储的 `options.operation-timeout` 可覆盖），建表类语句最多 5 分钟：在故障切换中或代理之后丢失的回应会让调用以暂时性错误失败并换掉这个连接，而不是一直挂住。连接池的驱动已设了更短的 `socketTimeout` 时保留它。查询保留自己的超时预算（`statement_timeout`）。

## 多个数据库 {#several-databases}

各聚合类型把数据放在不同数据库里的服务，命名它的存储实例，并把每个类型放到其中一个上：

```yaml
wow:
  stores:
    orders:
      type: postgres
      data-source: ordersDataSource
    archive:
      type: postgres
      data-source: archiveDataSource
  log:
    store: orders
  snapshot:
    stores: [orders]
  aggregates:
    "[shop.invoice]":
      log:
        store: archive
```

每个存储运行在它指定的 `DataSource` Bean 上，两个日志存储不能共用一个数据库。放到另一个存储上的聚合类型，把它的日志、它的订阅的检查点、它的失败台账和定时器都放在那里；租约和预留仍留在默认的日志存储上。

## 备份 {#backups}

日志是唯一的事实：快照、读模型和数仓都从它重建。把日志表与检查点、租约、台账、预留和定时器一起，作为一次一致的数据库备份来备份。
