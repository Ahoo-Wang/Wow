---
title: ClickHouse
description: 用数仓 Sink 把每个聚合的提交写入 ClickHouse，它保存的表、去重与保留、wow_catalog，以及从中提供数据集。
outline: deep
---

# ClickHouse

ClickHouse 是 Wow 的数仓。一个 Sink 把每个发布的聚合的提交复制进去，ClickHouse 从中推出当前状态和每个事件一行，服务的数据集也在那里查询。日志仍然是唯一的事实：数仓可以删掉，再从日志重新填充。本页连接一个服务，并介绍这些表。

## 连接服务 {#connect-a-service}

把 `wow-clickhouse` 放到类路径上，并设置 `wow.clickhouse.endpoint`，即服务器的 HTTP 端点；只有设置了它，ClickHouse 才会被装配。eShop 的 ordering 服务在它的 `clickhouse` profile 中这样做：

<!-- sample: examples/eshop/ordering/ordering-server/src/main/resources/application.yaml#clickhouse -->

```yaml
spring:
  config:
    activate:
      on-profile: clickhouse
wow:
  clickhouse:
    # The order stream is written to ClickHouse by the Sink (eshop_ordering_order_state_flat, ...), and the sales
    # dataset, META-INF/wow/dataset/eshop-ordering.sales.json, is queried there: /wow/dataset/eshop-ordering/sales/query.
    endpoint: http://localhost:8123
```

`wow.clickhouse.username`（`default`）和 `password` 用于登录，`wow.clickhouse.database`（`default`）存放这些表。Sink 写入 Manifest 发布的每个聚合；`wow.clickhouse.sink.enabled: false` 关闭它。

Sink 和数据集各自用这些设置打开一个客户端，并随节点关闭。想共用一个客户端，或自己也要用 ClickHouse 的应用，把客户端声明为 bean：两者就都用它，且从不关闭它。应用用 Spring 的 `Binder` 绑定 `wow.clickhouse`，用 `ClickHouseClients.of` 构建客户端，并随自己的上下文关闭它。分析目录服务就是这样做的：

<!-- sample: platform/analytics/catalog/server/src/main/kotlin/me/ahoo/wow/analytics/catalog/server/AnalyticsCatalogConfiguration.kt#shared-client -->

```kotlin
@Bean(destroyMethod = "close")
@ConditionalOnProperty(prefix = ClickHouseClients.PREFIX, name = ["endpoint"])
fun analyticsCatalogClickHouseClient(environment: Environment): Client {
    val settings = Binder.get(environment)
        .bind(ClickHouseClients.PREFIX, Bindable.mapOf(String::class.java, String::class.java))
        .orElse(emptyMap())
        .orEmpty()
    return ClickHouseClients.of(settings)
}
```

## 表 {#the-tables}

每个聚合类型一组，以它的上下文和聚合命名（`eshop_ordering_order`）：

| 表 | 保存 |
| --- | --- |
| `<ctx>_<agg>_state_events` | Sink 插入的基表：每次提交一行，带着它的事件和提交之后的状态。按提交的月份分区，按租户、聚合和版本排序 |
| `<ctx>_<agg>_state` | 每个聚合的当前状态，由物化视图从基表填充 |
| `<ctx>_<agg>_events` | 每个事件一行，由物化视图填充 |
| `<ctx>_<agg>_state_flat` | 当前状态的一个视图，状态的每个顶层标量都是一个有类型的列，供分析工具使用 |

`wow.aggregates.[<context>.<aggregate>].clickhouse.history.enabled: false` 只保留当前状态：Sink 随后直接写 `_state`，也就没有 `_state_events` 和 `_events`。`...history.ttl` 则让历史保留一段时间，而不是永久保留。

## 投递一次，读取一次 {#delivered-once-read-once}

Sink 是一个订阅，所以一次提交可能不止一次到达它：重试、重放、交接。有两个机制让数仓保持精确：

- 每张表记住它最近的 `wow.clickhouse.sink.deduplication-window`（1,000）个插入块，所以重试的插入会被丢弃，视图的表也一样。
- 超出这个窗口时，历史表是以聚合、版本和事件为键的 `ReplacingMergeTree`：重放的行会在合并中去掉，用 `FINAL` 读取时每次提交、每个事件都只计一次。

`wow.clickhouse.sink.batch-size`（1,000）是一次插入最多携带的提交数。

## schema 管理 {#schema-management}

`wow.clickhouse.ddl` 决定 Sink 在第一次写入之前以及之后不时怎样对待它的表：`auto`（默认）创建缺失的部分并校验其余部分；`validate` 只检查；`none` 两者都不做。被删除或被修改过的表会让写入失败，订阅会重试，直到它恢复成 Sink 期望的样子。

滚动升级期间，一个服务的两个版本可能同时写入两种状态 schema。展平视图记住创建它所用的定义的指纹，写入者只会用更新的定义替换它，所以重启的旧节点不会撤销新节点的视图。

## wow_catalog {#wow-catalog}

Sink 为它保存的每张表，在数据库的 `wow_catalog` 中追加一行，内容来自 Manifest 而不是表名：表及其角色（`state_events`、`state`、`events`、`state_flat`），它所属的服务、上下文和聚合，聚合的归属以及租户、所有者和空间所在的列，按路径列出的敏感值和引用，以及它的事件的修订号。分析工具和分析目录读取它，以了解一张表保存什么、怎样限定范围和脱敏。写入它失败只记日志，从不让 Sink 失败。

## 数据集 {#datasets}

服务在它的表之上声明数据集（`META-INF/wow/dataset/<context>.<dataset>.json`），节点在 ClickHouse 中查询它们：例如 ordering 基于它的订单的销售数据集。`wow.clickhouse.query.enabled` 提供它们，默认打开；`wow.clickhouse.query.settings.<name>` 设置每个数据集查询运行时的 ClickHouse 设置，例如 `max_memory_usage`。`wow.query.dataset.max-concurrent` 限制同时运行的数量，这样分析查询就不会占满 Sink 正在写入的数仓。

ClickHouse 无法连接或过载时（连接被拒绝或超时、查询过多、存活副本过少），数据集查询以 503 和 `Retry-After` 回答 `wow.storage.unavailable`，Sink 的订阅暂停它的分区直到 ClickHouse 恢复，不会搁置任何聚合。

## 重建数仓 {#rebuild-the-warehouse}

Sink 是一个有自己检查点的订阅，所以数仓从来不是任何东西唯一的副本：它总能从日志开头重新填充。
