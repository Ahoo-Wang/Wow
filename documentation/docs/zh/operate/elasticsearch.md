---
title: Elasticsearch
description: 在 Elasticsearch 上保存服务快照的一份副本以供查询，并通过 Sink 检索它的事件，而它的日志和主快照仍保存在 PostgreSQL 或 MongoDB 上。
---

# Elasticsearch

Elasticsearch 从不保存 Wow 日志：每个聚合的日志都保存在 PostgreSQL 或 MongoDB 上，它们是事实的来源。Elasticsearch 得到的是副本：快照，让快照查询在搜索引擎上运行；以及通过 Sink 写入的事件流，让 `events` 模型可以跨聚合检索。两者随时都可以从日志重建。模块是 `wow-elasticsearch`。

## `elasticsearch` 类型的存储 {#a-store-of-type-elasticsearch}

`elasticsearch` 类型的存储基于应用的底层 `Rest5Client` 打开，这个客户端由 Spring Boot 的 Elasticsearch 支持配置。在日志的存储旁边为它命名，把它加到某个聚合的快照存储中、排在命令加载状态所用的存储之后，并让这个聚合的查询指向它：

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
        stores: [main, search]
      query:
        snapshot:
          store: search
        events:
          store: search
```

| 配置项 | 设置 | 默认值 |
| --- | --- | --- |
| `wow.stores.<name>.client` | 存储写入时使用的 `Rest5Client` bean | 唯一的那个 `Rest5Client` |
| `wow.stores.<name>.query-client` | 第二个 `Rest5Client`，即查询专用的连接池 | 存储的 `client` |
| `wow.stores.<name>.index-prefix` | 每个索引名的前缀：`shop_wow_snapshot__…`、`shop_wow_events__…` | 无 |
| `wow.stores.<name>.refresh` | 快照写入何时变得可检索：`none`（在索引的刷新间隔内）、`wait-for`（写入返回时）或 `immediate` | `none` |
| `wow.stores.<name>.ddl` | 是否创建索引，并为状态和事件的 body 字段建立映射 | `wow.ddl` |
| `wow.stores.<name>.reindex-requests-per-second` | 为后来映射的字段重新索引已有文档的速度（[见下文](#field-mapped-later)）；`-1` 表示不限 | `1000` |
| `wow.aggregates.[<context>.<aggregate>].snapshot.stores` | 聚合快照所在的存储 | `wow.snapshot.stores`，否则为日志的存储 |
| `wow.aggregates.[<context>.<aggregate>].snapshot.primary` | 其中供写端和状态读取读的那个；从不是 Elasticsearch | 类型自己列出 `snapshot.stores` 时为其中第一个；否则为 `wow.snapshot.primary`，再否则为 `wow.snapshot.stores` 的第一个 |
| `wow.aggregates.[<context>.<aggregate>].query.snapshot.store` | 它的快照查询所读取的存储 | 它的主快照存储 |
| `wow.aggregates.[<context>.<aggregate>].query.events.store` | 它的 `events` 查询所读取的存储 | 日志的存储 |

每个索引都在它的 `_meta` 中记录自己的布局，并拒绝期望另一种布局的存储。

## 快照查询 {#snapshot-queries}

`query.snapshot.store` 指向 Elasticsearch 存储的聚合，它的快照查询就从那里提供，使用与任何后端相同的[查询语言](../reference/query-language)和能力描述符。命令的 `snapshot` 等待从不覆盖这个存储：它在主快照存储的订阅写入该提交时结束，所以此时在这里检索未必能找到那个版本，而默认情况下写入在索引的刷新间隔内才变得可检索（`refresh: none`）。命令若必须在这里找到自己的快照，就等待这个存储自己的订阅，`Wow-Wait: handled(wow.snapshot:<store>)`，并把存储设为 `refresh: wait-for`，这样它所等待的写入在等待结束时就可以检索。存储的查询在它的查询舱壁下运行：`wow.stores.<name>.query.max-concurrent`，默认为写连接池每个路由连接数的四分之一，至少为 2；有 `query-client` 时则是它的整个连接池。超出的查询会短暂等待，然后以 429 `wow.query.busy` 回答。

状态或事件 body 里的 map（键不固定的对象，如 `Map<String, Item>` 属性）映射为一个 `flattened` 字段，所以它某个键下的值只能做存在性与精确匹配的过滤：没有范围、相对时间、排序和聚合。描述符的 `dynamic` 模式列出的正是这些算子。

快照的文档和事件检索的文档还保存 `accessPresent`：聚合持有的每个访问属性下为 `true`，单值属性有值时，多值属性无论空或不空。Elasticsearch 不为空数组建立任何索引，所以访问策略从这里读出某个属性是否存在，在 Elasticsearch 上授予的聚合与在任何存储上、与状态读取时相同。属性声明之前写下的文档没有它，就像属性在其中缺失一样，直到聚合的下一次提交或访问遍历再次写它。

## 全文检索 {#full-text-search}

状态或事件 body 中的字符串字段映射为 `keyword`：按整个值匹配，等值、`contains`、`startsWith`、排序和分组都是这样匹配的。只有 schema 把它标为供人按词检索的文字时，它才可以按词检索：属性上的 `@QueryText`（[描述类型说明不了的东西](../guide/queries#describe-what-types-cannot)）、Manifest 中的 `x-wow-text`、代码中声明的 `text()`。标记过的字段会得到一个 `text` 子字段，由索引默认的分析器分析，能力描述符把它列入 `search` 可以指定的字段：`record.search.fields`，或它所在元素的。

- **指定了字段的 `search`** 读这些字段，每个都必须标记过；否则回答 `unsupported-capability`。
- **没有指定字段的 `search`** 读记录中（元素之外）标记过的字段，即 `record.search.fields` 列出的那些。一个都没有标记时，回答 `model-search-unsupported`。
- **短语检索** 要求它读的每个字段都支持短语，每个 `text` 子字段都支持。

只标记供人按词检索的文字，例如名称、描述、备注或原因。ID、枚举、编码和引用按整个值匹配，为它们的每个词建索引只会占用空间和索引时间。

后来才给字段加上标记时，会给索引加上 `text` 子字段，并重新索引此前写入的文档（[见下文](#field-mapped-later)）。去掉标记后，子字段留在映射中，但该字段不再能按词检索。一个索引名对应多个索引（例如滚动索引上的别名），而只有其中一部分映射了子字段时，该字段仍可按整个值匹配，等每个索引都映射了它才能按词检索。

## 后来映射的字段 {#field-mapped-later}

状态和事件 body 的字段在查询第一次加载聚合的 schema 时建立映射（`ddl: auto` 下），所以 schema 后来新增的字段，是在已经有文档的索引上建立映射的。这些文档会在后台由一个 `_update_by_query` 任务重新索引，任务保持每个文档原样，好让新字段也能找到它们。任务结束之前，按新字段过滤、排序或分组的查询会漏掉映射之前写入的文档：结果是不完整的。

任务以每秒 `reindex-requests-per-second` 个文档的速度运行，默认 1,000，切片数与索引的分片数相同。按默认值，一百万个文档约需 17 分钟，一千万个约需 3 小时。集群负载高时调低它；需要尽快用上新字段时调高，或设为 `-1` 不限速。

存储在任务开始时记录它的 id：`Elasticsearch index [<index>] mapped [<fields>]: … task [<node>:<id>]`。它不记录任务何时结束，那只能轮询任务才知道。用 Elasticsearch 自己的接口跟踪任务，并在运行中调整速度：

```bash
curl "$ELASTICSEARCH_URL/_tasks/<node>:<id>"
curl -X POST "$ELASTICSEARCH_URL/_update_by_query/<node>:<id>/_rethrottle?requests_per_second=5000"
```

每个已有文档都重新索引后，`GET _tasks/<id>` 回答 `"completed": true`。

## 事件检索 {#event-search}

事件检索 Sink 把每次提交写成 `[<prefix>_]wow_events__<context>__<aggregate>` 中的一个文档：`events` 模型的记录、它的系统字段，以及作为嵌套数组 `events` 的事件，每个事件带着提交时的名字、修订号和 body。文档的 ID 是提交的标识，即租户、聚合 ID 和版本，所以再次写入同一次提交不会改变任何东西。body 字段根据 Manifest 的事件 schema 建立映射；两个事件以不同类型声明的字段不建立映射。

`query.events.store` 指向 Elasticsearch 存储的聚合，它的 `events` 查询从那个索引提供：事件条件是一个 nested 查询，`expand("events")` 聚合是一个 nested 聚合，不指明聚合的查询会检索索引，而不是扫描日志。索引显示的是 Sink 已写入的内容：一次提交在 Sink 写入并且索引刷新之后才可检索，刷新默认为一秒。

每个 Sink 都作为名为 `wow.elasticsearch-sink.<aggregate>` 的订阅运行，所以它从检查点续读，也可以从日志开头重新开始。宿主会自行启动这些 Sink，不需要单独的配置：对 `query.events.store` 指向的每个 Elasticsearch 存储，它把正好这些聚合的流写入该存储，不论服务是否发布它们。

## 存储组合 {#store-combinations}

每个聚合类型各自选择存储：日志和主快照在 PostgreSQL 或 MongoDB 上（`wow.log.store`、`wow.aggregates.[<context>.<aggregate>].log.store`），快照的副本和事件检索在 Elasticsearch 上，如上面的示例所设。由此有两点：

- **命令和状态读取留在主存储上。** 命令从主快照存储和其后的日志加载状态，状态读取（`GET …/state`）读取主存储，所以两者都不必等待 Elasticsearch 的刷新。每个快照存储由它自己的一个订阅从日志写入，默认快照存储（`wow.snapshot.stores` 的第一个，否则为日志的存储）是 `wow.snapshot`，其余每个是 `wow.snapshot:<store>`，并且只保留它收到的最高版本，所以副本只会落后，并且可以重建。宿主拒绝把 Elasticsearch 作为聚合的主快照存储或唯一的快照存储，并指出相应的配置项。
- **事件查询跟随 Sink。** 聚合的 `query.events.store` 指向该存储后，它的 `events` 查询读取 Elasticsearch，因此看到的是 Sink 已写入的内容：提交在提交后不久才可检索，而不是立即可检索。它的快照可以留在日志的存储上，两项设置相互独立。

## 重建 {#rebuild}

两种副本都来自日志。快照索引通过重建聚合的快照来重建（[重建与重放](./replay)）。事件索引通过从日志开头启动它的 Sink 订阅来重新填充。两者都不需要自己的备份（[备份与恢复](./backup)）。

## 从 9.x 而来 {#from-9-x}

9.x 的 Elasticsearch 事件存储不是迁移的来源：`migrate-data` 只读取 9.x 的 MongoDB 事件存储，所以先在 9.x 之内把事件流移到 MongoDB（[迁移数据](../migrate/data)）。9.x 的索引也不会被读取。为 Wow 10 设置它自己的 `index-prefix` 或集群：日志迁移之后，快照从日志写出，从头启动的事件检索 Sink 会填充事件索引。
