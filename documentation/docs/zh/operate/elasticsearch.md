---
title: Elasticsearch
description: 把服务的快照及其查询放在 Elasticsearch 上，并通过 Sink 检索它的事件，而它的日志仍保存在 PostgreSQL 或 MongoDB 上。
---

# Elasticsearch

Elasticsearch 从不保存 Wow 日志：每个聚合的日志都保存在 PostgreSQL 或 MongoDB 上，它们是事实的来源。Elasticsearch 得到的是副本：快照，让快照查询在搜索引擎上运行；以及通过 Sink 写入的事件流，让 `events` 模型可以跨聚合检索。两者随时都可以从日志重建。模块是 `wow-elasticsearch`。

## `elasticsearch` 类型的存储 {#a-store-of-type-elasticsearch}

`elasticsearch` 类型的存储基于应用的底层 `Rest5Client` 打开，这个客户端由 Spring Boot 的 Elasticsearch 支持配置。在日志的存储旁边为它命名，并把某个聚合的快照发往它：

<!-- sample: fragment -->

```yaml
wow:
  stores:
    main: { type: postgres }
    search:
      type: elasticsearch
      index-prefix: shop
  log:
    store: main
  aggregates:
    "[shop.order]":
      snapshot:
        store: search
      query:
        events:
          store: search
```

| 配置项 | 设置 | 默认值 |
| --- | --- | --- |
| `wow.stores.<name>.client` | 存储写入时使用的 `Rest5Client` bean | 唯一的那个 `Rest5Client` |
| `wow.stores.<name>.query-client` | 第二个 `Rest5Client`，即查询专用的连接池 | 存储的 `client` |
| `wow.stores.<name>.index-prefix` | 每个索引名的前缀：`shop_wow_snapshot__…`、`shop_wow_events__…` | 无 |
| `wow.stores.<name>.refresh` | 快照写入何时变得可检索：`wait_for`、`true` 或 `false` | `wait_for` |
| `wow.stores.<name>.ddl` | 存储是否创建它的索引：`auto`、`validate`、`none` | `wow.ddl` |
| `wow.query.index.ddl` | 是否为状态和事件的 body 字段建立映射 | `auto` |
| `wow.aggregates.[<context>.<aggregate>].snapshot.store` | 聚合快照所在的存储 | 日志的存储 |
| `wow.aggregates.[<context>.<aggregate>].query.events.store` | 它的 `events` 查询所读取的存储 | 日志的存储 |

每个索引都在它的 `_meta` 中记录自己的布局，并拒绝期望另一种布局的存储。

## 快照查询 {#snapshot-queries}

快照在 Elasticsearch 上的聚合，它的快照查询就从那里提供，使用与任何后端相同的[查询语言](../reference/query-language)和能力描述符。使用默认的 `refresh: wait_for` 时，快照在写入返回时就可以检索。存储的查询在它的查询舱壁下运行：`wow.stores.<name>.query.max-concurrent`，默认为写连接池每个路由连接数的四分之一，至少为 2；有 `query-client` 时则是它的整个连接池。超出的查询会短暂等待，然后以 429 `wow.query.busy` 回答。

## 事件检索 {#event-search}

事件检索 Sink 把每次提交写成 `[<prefix>_]wow_events__<context>__<aggregate>` 中的一个文档：`events` 模型的记录、它的系统字段，以及作为嵌套数组 `events` 的事件，每个事件带着提交时的名字、修订号和 body。文档的 ID 是提交的标识，即租户、聚合 ID 和版本，所以再次写入同一次提交不会改变任何东西。body 字段根据 Manifest 的事件 schema 建立映射；两个事件以不同类型声明的字段不建立映射。

`query.events.store` 指向 Elasticsearch 存储的聚合，它的 `events` 查询从那个索引提供：事件条件是一个 nested 查询，`expand("events")` 聚合是一个 nested 聚合，不指明聚合的查询会检索索引，而不是扫描日志。索引显示的是 Sink 已写入的内容：一次提交在 Sink 写入并且索引刷新之后才可检索，刷新默认为一秒。

每个 Sink 都作为名为 `elasticsearch-sink.<aggregate>` 的订阅运行，所以它从检查点续读，也可以从日志开头重新开始。在 10.0 中，Spring Boot 宿主不会自行启动事件检索 Sink：需要事件检索的应用自己启动它们。

## 重建 {#rebuild}

两种副本都来自日志。快照索引通过重建聚合的快照来重建（[重建与重放](./replay)）。事件索引通过从日志开头启动它的 Sink 订阅来重新填充。两者都不需要自己的备份（[备份与恢复](./backup)）。

## 从 9.x 而来 {#from-9-x}

9.x 的 Elasticsearch 事件存储不是迁移的来源：`migrate-data` 只读取 9.x 的 MongoDB 事件存储，所以先在 9.x 之内把事件流移到 MongoDB（[迁移数据](../migrate/data)）。9.x 的索引也不会被读取。为 Wow 10 设置它自己的 `index-prefix` 或集群：日志迁移之后，快照从日志写出，从头启动的事件检索 Sink 会填充事件索引。
