---
title: Elasticsearch
description: 在 Elasticsearch 上保存服务的快照，作为供查询的副本或聚合唯一的快照存储，并通过 Sink 检索它的事件，而它的日志仍保存在 PostgreSQL 或 MongoDB 上。
---

# Elasticsearch

Elasticsearch 从不保存 Wow 日志：每个聚合的日志都保存在 PostgreSQL 或 MongoDB 上，它们是事实的来源。Elasticsearch 得到快照，让快照查询在搜索引擎上运行：聚合自己的快照存储旁的一份副本，或者就是那个存储（[下文](#only-snapshot-store)）。它还得到通过 Sink 写入的事件流，让 `events` 模型可以跨聚合检索。两者随时都可以从日志重建。模块是 `wow-elasticsearch`。

## `elasticsearch` 类型的存储 {#a-store-of-type-elasticsearch}

`elasticsearch` 类型的存储基于应用的底层 `Rest5Client` 打开，这个客户端由 Spring Boot 的 Elasticsearch 支持配置。在日志的存储旁边为它命名，并让某个聚合的查询指向它，这个存储就为该聚合的快照查询保存一份快照副本：

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
| `wow.aggregates.[<context>.<aggregate>].snapshot.store` | 聚合快照所在的存储，命令从它加载状态，状态读取也读它；也可以是 Elasticsearch（[下文](#only-snapshot-store)） | `wow.snapshot.store`，否则为日志的存储 |
| `wow.aggregates.[<context>.<aggregate>].query.snapshot.store` | 它的快照查询所读取的存储；不是它自己的存储时，这个存储就为这些查询保存一份它的快照副本 | 它自己的快照存储 |
| `wow.aggregates.[<context>.<aggregate>].query.events.store` | 它的 `events` 查询所读取的存储 | 日志的存储 |

每个索引都在它的 `_meta` 中记录自己的布局，并拒绝期望另一种布局的存储。

## 快照查询 {#snapshot-queries}

`query.snapshot.store` 指向 Elasticsearch 存储的聚合，它的快照查询就从那里提供，使用与任何后端相同的[查询语言](../reference/query-language)和能力描述符。存储的查询在它的查询舱壁下运行：`wow.stores.<name>.query.max-concurrent`，默认为写连接池每个路由连接数的四分之一，至少为 2；有 `query-client` 时则是它的整个连接池。超出的查询会短暂等待，然后以 429 `wow.query.busy` 回答。

### 读到自己的写入 {#reading-your-own-write}

命令的 `snapshot` 等待从不覆盖副本：聚合自己的快照存储包含该提交时它就结束。它的 `queryable` 等待则在副本的订阅 `wow.snapshot:<store>` 写入该提交后结束（[等待](../concepts/waits)）。在默认的 `refresh: none` 下，检索在这次写入之后、索引的刷新间隔内才能看到该提交，所以提交命令后重新加载列表的页面仍可能看不到它。

提交后重新加载列表的页面，在 `refresh: wait-for` 的存储上等待 `queryable`。这样副本的每次写入都在检索能看到它之后才返回，页面重新加载的列表就包含这次提交。代价落在页面等待的每个命令上：等待最多晚结束一个刷新间隔。它还把副本的订阅限制为每个刷新间隔每条写通道一批：四条通道，每批至多 `wow.snapshot.write-batch` 个快照（默认 256），在 Elasticsearch 默认一秒的刷新间隔下约为每秒一千个聚合。超过这个量，副本就会落后。

另一种做法是让这个类型的查询留在它自己的存储上，不要副本。这样它的列表读取命令所写的数据库，`queryable` 与 `snapshot` 是同一个等待，也没有任何东西等待刷新。只为搜索引擎比数据库更擅长的查询选择副本：全文检索、对大量数据的聚合。

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

## 作聚合唯一的快照存储 {#only-snapshot-store}

聚合类型自己的快照存储可以是 Elasticsearch 存储，它的日志仍在 MongoDB 或 PostgreSQL 上。这时它的状态只存一份，就在它的快照查询读取的索引里：

<!-- sample: fragment -->

```yaml
wow:
  stores:
    main: { type: mongo }
    search: { type: elasticsearch }
  log:
    store: main
  aggregates:
    "[shop.order]":
      snapshot:
        store: search
```

- **命令按 ID 从它加载。** 命令用实时 get 从索引加载状态，再从日志的存储读日志的尾部。状态读取（`GET …/state`）也这样读这个索引。无论 `refresh` 取什么，写入一返回，按 ID 就能立刻读回。
- **它的默认等待止于 Elasticsearch。** 索引包含该提交时 `snapshot` 就结束。这个类型的查询读的是同一个索引，所以 `queryable` 是同一个等待。在默认的 `refresh: none` 下，检索要在这之后的一个索引刷新间隔内才看到该提交。提交命令后重载列表的页面可能读不到它，按 ID 读取则不会：刚创建的商品立刻列出时可能不在列表里，而按 ID 读它的状态已经有了。
- **`refresh: wait-for` 为这个存储上各类型的每条命令补上这个空隙。** 每次写入都等到检索看得到才返回，所以每条按默认等待的命令都可能晚至多一个刷新间隔才得到应答。这个存储的订阅也像副本那样，每个刷新间隔每条写入通道至多写一批（[上文](#reading-your-own-write)）。超过这个上限它就会落后，未命中状态缓存的命令要重放更长的一段日志尾部。
- **宕机时节点退出服务。** 类型自己的快照存储不论什么类型都在命令的路径上。它持续失败达 `wow.health.stores.down-after` 后，节点即未就绪。仍到达节点的命令照样提交，加载时重放日志，但默认等待会超时（`504 wow.wait.timeout`，结果为已提交）。状态读取应答 `503 wow.storage.unavailable`。每次失败的加载都计入 `wow.store.fallbacks`（`snapshot-load`），只有第一次记为 warning 日志。这个存储的订阅暂停，Elasticsearch 恢复后再追上。命令不能依赖 Elasticsearch 的类型，把快照留在日志的数据库上，另在 Elasticsearch 上加一份副本，它宕机时只是检索停下（[选择存储](./choosing-storage#elasticsearch-is-never-the-log)）。

- **索引拒绝的状态会让它的聚合停放。** 状态的映射没有动态字段，所以新字段或其他类型的值只是不被索引。索引仍然拒绝的文档会让这个聚合在该存储的订阅中立即停放，每种存储对它永远存不下的快照都这样处理（[停放的聚合](./replay#parked-aggregates)）。对 Elasticsearch，是映射里是值的地方出现对象或反过来，或访问属性超过索引的字段上限；对 MongoDB，是超过 16 MiB 文档上限或 BSON 存不下的状态；对 PostgreSQL，是它作为数据拒绝的值。Elasticsearch 是这个聚合唯一的快照存储，所以此后它不再有更新的快照：
  - 它的命令照样提交，默认等待以失败结束（`wow.subscription.parked`）；
  - 未命中状态缓存的命令从最后写入的快照重放它的日志，没有快照时从头重放；
  - 它的状态读取应答那个快照，没有时应答重放的日志，列表显示那个快照或者没有它。

  修正状态或映射后，重试停放的条目。
- **PostgreSQL 日志会失去它的快路径。** 只有类型的日志、快照和检查点都在同一个 PostgreSQL 存储上时，命令才能在一次往返中读出快照及其后的日志尾部。也只有这时，快照订阅的写入才不必等待自己的 WAL 刷盘就提交，因为其后保存的检查点覆盖了它们。MongoDB 日志两者都没有，所以什么也不失去。
## 存储组合 {#store-combinations}

每个聚合类型各自选择存储：日志在 PostgreSQL 或 MongoDB 上，快照在那里或在 Elasticsearch 上（`wow.log.store`、`wow.snapshot.store`，以及聚合的 `log.store` 和 `snapshot.store`），快照的副本和事件检索在 Elasticsearch 上，如上面的示例所设。由此有两点：

- **命令和状态读取留在聚合自己的存储上。** 命令从聚合的快照存储和其后的日志加载状态，状态读取（`GET …/state`）按 ID 读取这个存储，所以两者都不必等待 Elasticsearch 的刷新。每个快照存储由它自己的一个订阅从日志写入，无论它保存某个类型自己的快照还是一份副本：默认快照存储（`wow.snapshot.store`，否则为日志的存储）是 `wow.snapshot`，其余每个是 `wow.snapshot:<store>`。它只保留收到的最高版本，所以副本只会落后，并且可以重建。宿主只拒绝把 Elasticsearch 作为日志存储，并指出相应的配置项。
- **事件查询跟随 Sink。** 聚合的 `query.events.store` 指向该存储后，它的 `events` 查询读取 Elasticsearch，因此看到的是 Sink 已写入的内容：提交在提交后不久才可检索，而不是立即可检索。它的快照可以留在日志的存储上，两项设置相互独立。

## 重建 {#rebuild}

两种副本都来自日志。快照索引通过重建聚合的快照来重建（[重建与重放](./replay)）。事件索引通过从日志开头启动它的 Sink 订阅来重新填充。两者都不需要自己的备份（[备份与恢复](./backup)）。

## 从 9.x 而来 {#from-9-x}

9.x 的 Elasticsearch 事件存储不是迁移的来源：`migrate-data` 只读取 9.x 的 MongoDB 事件存储，所以先在 9.x 之内把事件流移到 MongoDB（[迁移数据](../migrate/data)）。9.x 的索引也不会被读取。为 Wow 10 设置它自己的 `index-prefix` 或集群：日志迁移之后，快照从日志写出，从头启动的事件检索 Sink 会填充事件索引。
