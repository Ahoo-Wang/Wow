---
title: migrate-data 命令行
description: migrate-data 工具的命令、选项、退出码、配置文件、迁移映射文件和检查点文件。
outline: deep
---

# migrate-data 命令行

`migrate-data` 把 9.x 服务的数据搬进 V10。本页列出它读取的一切和它做的一切；步骤见[迁移数据](../migrate/data)。

## 运行 {#running}

```bash
./gradlew :migrate-data:run --args="<command> --config <file> [options]"
```

在你要迁移到的那个 Wow 版本的检出目录里运行它。它读取 9.x 的 MongoDB 事件存储，写入 PostgreSQL 或 MongoDB 上的 V10 日志。

## 命令 {#commands}

| 命令 | 选项 | 做什么 |
| --- | --- | --- |
| `check` | | 对照 9.x 存储和 V10 的 Manifest 检查映射文件。不写任何东西。 |
| `import` | `--dry-run`、`--restart`、`--snapshots` | 先检查，然后导入每个已映射的 9.x 聚合，再导入预留键和未解决的补偿执行，从检查点续做。 |
| `tail` | `--once` | 追踪 9.x 在导入之后提交的内容，直到被停止。 |
| `compare` | `--sample <percent>`、`--restart` | 把每个 9.x 快照与 V10 在快照版本上从日志重建的状态比较，然后比较预留和停放的条目。 |
| `snapshot` | `--restart` | 为每个导入的聚合写入它最后一个版本的 V10 快照。 |
| `backfill-tags` | `--restart`、`--force` | 为每条追加时没有标签的 V10 流记录标签，然后改写没有标签就被索引的 Elasticsearch 事件文档。 |

| 选项 | 含义 |
| --- | --- |
| `--config <file>` | 配置文件。每个命令都必须指定。 |
| `--dry-run` | `import` 读取并转换每条流，但不写入，也不保存检查点。 |
| `--restart` | 忽略上一轮停在哪里，从第一个实例重新遍历每个实例。与 `import` 一起用，就是 9.x 停止写入之后最后那一次完整遍历；它还会对账之前导入的预留键和停放的条目。 |
| `--snapshots` | 在 `import` 之后运行 `snapshot`。 |
| `--once` | `tail` 跑一轮就结束。 |
| `--sample <percent>` | `compare` 只比对这个比例的实例，按租户和 ID 的哈希挑选，所以每次运行比对的都是同一批。换一个比例会重新开始比对。 |
| `--force` | 即使某个日志里仍有没有标签的流，`backfill-tags` 也改写 Elasticsearch 文档，这些流对应的文档保持原样。 |

命令不接受的选项属于用法错误。

### 退出码 {#exit-codes}

| 代码 | 含义 |
| --- | --- |
| `0` | 完成。 |
| `1` | 失败：检查未通过，或某一步停下了。 |
| `2` | 用法错误。 |
| `3` | `compare` 发现了差异，在这次运行中，或在它续做的之前的运行中。 |

### 输出 {#output}

需要关注的行以一个标记开头：

| 标记 | 含义 |
| --- | --- |
| `PROBLEM` | 检查发现了会阻止导入的问题。 |
| `WARNING` | 检查发现了不阻止导入的问题，例如没有被用到的映射。 |
| `DIFFERS` | `compare` 发现了差异：状态、预留或停放的条目。 |
| `UNRESOLVED` | 某个预留键或补偿执行无法安置，没有导入。 |
| `RELEASED` | 之前导入的某个预留被释放了，因为 9.x 不再确认它属于那个所有者。 |
| `RESOLVED` | 之前导入的某个停放条目被标为已解决，因为它对应的 9.x 执行已经解决。 |

## 配置文件 {#configuration-file}

一个 JSON 文件。相对路径相对于该文件所在目录解析。写作 `env:NAME` 的字符串从环境变量 `NAME` 读取。工具没有定义的属性会被拒绝。

<!-- sample: migration/migrate-data/src/test/resources/docs/migrate-data.json -->

```json
{
  "map": "migration-map.json",
  "manifests": ["../order-service/build/wow/manifest.json"],
  "checkpoint": "migrate-data.checkpoint.json",
  "source": {
    "uri": "mongodb://mongo-9x:27017",
    "database": "order_db"
  },
  "target": {
    "type": "postgres",
    "jdbcUrl": "jdbc:postgresql://postgres:5432/wow",
    "username": "wow",
    "password": "env:WOW_PG_PASSWORD",
    "poolSize": 16
  },
  "partitions": { "example": 64 },
  "classpath": ["../order-service/build/install/order-service/lib"],
  "compensation": {
    "database": "compensation_db",
    "service": "order-service"
  }
}
```

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `map` | 必填 | 迁移映射文件。 |
| `manifests` | 必填 | 流要去往的各服务合并后的 V10 Manifest。 |
| `checkpoint` | `migrate-data.checkpoint.json` | 检查点文件。 |
| `source.uri`、`source.database` | 必填 | 9.x 的 MongoDB 事件存储。 |
| `source.createCommitTimeIndex` | `false` | 允许 `tail` 在每个事件流集合上创建它需要的 `(createTime, _id)` 索引。否则没有索引时它拒绝启动，并打印创建命令。 |
| `target.type` | 必填 | `postgres` 或 `mongo`。 |
| `target.jdbcUrl`、`target.username`、`target.password` | | PostgreSQL 目标。 |
| `target.poolSize` | `16` | 连接 PostgreSQL 目标的最大连接数。 |
| `target.uri`、`target.database` | | MongoDB 目标，必须是副本集。 |
| `partitions` | | 每个 V10 上下文的分区数，必须与服务自己的相同。 |
| `classpath` | 工具自己的类路径 | 服务的 V10 领域模块，每项是一个 jar，或者一个包含类或 jar 的目录。`compare`、`snapshot` 和 `backfill-tags` 用它们的模型重建状态。模型需要的依赖用替身代替：工具不运行任何决定函数。 |
| `compensation.database` | | 9.x 补偿服务的数据库。不配置 `compensation` 时，不读取补偿服务的任何内容。 |
| `compensation.uri` | 与来源相同 | 该数据库所在的服务器。 |
| `compensation.service` | | 接收未解决执行的 V10 服务，其失败台账会收到它们：填它的 `wow.service` 或 `spring.application.name`。 |
| `elasticsearch.uri`、`elasticsearch.indexPrefix` | | 服务的事件索引所在的集群，以及索引前缀。只有 `backfill-tags` 写入它。 |
| `pageSize` | `500` | 两个检查点之间导入的实例数；追尾一次读取的流数。 |
| `streamBatch` | `200` | 一次从 9.x 存储读取的同一实例的流数。 |
| `concurrency` | `8` | 同时导入的实例数。 |
| `lookbackMillis` | `300000` | 追尾在它读到的最后提交时间之前，再往回扫描多远。 |
| `pollIntervalMillis` | `1000` | 两轮追尾之间的间隔。 |

如果一个服务的各聚合把日志放在不同的 V10 存储里，就按存储分别运行一次，每次使用一份只含该存储所存聚合的映射文件。

## 映射文件 {#the-map}

`migration-map.json`（格式 1）记录每个 9.x 名字和形状怎样变成 V10 的名字和形状。`wow-migrate` Skill 在改写源码时写出它；工具在写入任何东西之前检查它。格式没有定义的属性会被拒绝。

<!-- sample: migration/migrate-data/src/test/resources/docs/migration-map.json -->

```json
{
  "format": 1,
  "headers": { "app_id": "app_id" },
  "aggregates": [
    {
      "from": "order-service.order",
      "to": "example.order",
      "commands": [
        { "from": "create_order", "to": "CreateOrder" },
        { "from": "pay_order", "to": "PayOrder" }
      ],
      "events": [
        {
          "from": "order_created",
          "fromRevision": "0.0.1",
          "to": "OrderCreated",
          "toRevision": 1,
          "upgrade": "upgradeOrderCreatedFrom1"
        },
        { "from": "order_paid", "to": "OrderPaid" },
        {
          "from": "owner_transferred",
          "to": "wow.lifecycle.OwnerTransferred",
          "fields": { "toOwnerId": "ownerId" }
        },
        { "from": "order_archived", "to": "OrderArchived", "retire": true },
        { "from": "order_noted", "drop": true }
      ],
      "state": {
        "fields": { "orderStatus": "status" },
        "ignore": ["payable"]
      },
      "reservations": [
        { "from": "order_no", "namespace": "order.no", "tenancy": "SYSTEM", "ownerId": "orderId" }
      ]
    }
  ],
  "subscriptions": [
    { "from": "order-service.OrderSaga", "functions": { "onEvent": "onOrderCreated" } },
    { "from": "order-service.OrderProjector", "to": "OrderReports" }
  ]
}
```

不带点的 V10 名字是聚合命名空间里的简单名（`OrderCreated` 即 `example.order.OrderCreated`）；带点的是完整名，例如 `wow.lifecycle.OwnerTransferred`。

### 顶层 {#top-level}

| 属性 | 含义 |
| --- | --- |
| `format` | `1`。 |
| `aggregates` | 每个 9.x 聚合一项；见下文。 |
| `headers` | 作为请求事实保留的 9.x 流请求头，9.x 键对应 V10 名字。其他请求头都被丢弃。9.x 框架请求头（`trace_id`、`upstream_id`、`upstream_name`、`command_operator`、等待请求头）不能列出：它们各自变成单独的字段，或被丢弃。 |
| `subscriptions` | 有未解决补偿执行的 9.x 处理器，映射到 V10 订阅；见下文。 |

### 聚合 {#aggregates}

| 属性 | 含义 |
| --- | --- |
| `from` | 9.x 聚合，按它的流记录的 `contextName.aggregateName`。 |
| `to` | V10 聚合类型 `context.aggregate`。 |
| `commands` | `{from, to}`：流记录的每个 9.x 命令名（它的 `upstream_name` 请求头）及其 V10 命令。存储里出现的每个命令名都需要一项。 |
| `defaultCommand` | 没有记录命令名的流所对应的 V10 命令。不配置时，这样的流无法通过检查。 |
| `events` | 每个 9.x 事件；见下文。 |
| `state` | 9.x 快照的状态怎样读成 V10 的状态，只用于 `compare`：`fields` 重命名顶层字段，9.x 名字对应 V10 名字；`ignore` 在两侧都忽略某些字段，按 V10 名字，点分路径可以深入对象、穿过数组。只对按设计只存在于一侧的字段使用 `ignore`。 |
| `reservations` | 值中指明该聚合某个实例的每个 9.x `PrepareKey`；见下文。存储里的每个 `PrepareKey` 都需要一项。 |

### 事件 {#events}

| 属性 | 含义 |
| --- | --- |
| `from` | 9.x 事件名。 |
| `fromRevision` | 这一项映射的 9.x 修订号；不写时匹配任意修订号。 |
| `to` | V10 事件名；对退役事件，是退役的名字。 |
| `toRevision` | 9.x 载荷在 V10 中对应的修订号，默认 1：9.x 的形状就是修订号 1，V10 的升级函数把它一路读到当前修订号。不能超过 Manifest 中的修订号。 |
| `fields` | 重命名载荷的顶层字段，9.x 名字对应 V10 名字。用于形状由 V10 固定的系统事件，例如 9.x 的 `OwnerTransferred.toOwnerId`；领域事件的变化应放在它的升级函数里。 |
| `upgrade` | Skill 写的升级函数，记录下来供报告使用；工具不运行它。 |
| `retire` | 把记录按原样以 `to` 这个名字写入，V10 聚合把这个名字列为退役，所以加载时跳过它，流也保留它的版本。`to` 在 Manifest 中没有退役时，检查失败。 |
| `drop` | 把这个事件从它的流中去掉。如果一条流的事件全被丢弃，这个版本就是空的，检查会拒绝：这样的事件要用 `retire` 映射。 |

### 预留 {#reservations}

9.x 把每个 `PrepareKey` 存在集合 `prepare_<name>` 里，每个键一个文档。永久准备的键变成已确认的预留；有存活时间的键或已过期的键被跳过。

| 属性 | 含义 |
| --- | --- |
| `from` | 9.x `PrepareKey` 的名字。 |
| `namespace` | 决定函数预留这个键所用的 V10 命名空间。 |
| `tenancy` | `TENANT`（默认：在所有者的租户内唯一）或 `SYSTEM`（跨租户唯一，9.x 的每个键都是这样）。必须与 V10 代码预留时用的租户范围一致。 |
| `ownerId` | 所有者 ID 在 9.x 值中的点分路径；不写时，值本身就是 ID。所有者的租户是它的 9.x 流所记录的租户。 |

所有者没有 9.x 流、或者其 ID 出现在多个租户的流里的键，标为 `UNRESOLVED`。在 V10 中已被另一个所有者持有、且不是这个工具预留的键，会让导入停下。

### 订阅 {#subscriptions}

V10 订阅的名字是 `<processor>.<function>`。

| 属性 | 含义 |
| --- | --- |
| `from` | 9.x 处理器，按 9.x 执行记录的 `contextName.processorName`。 |
| `to` | V10 处理器名；默认与 9.x 的相同。 |
| `functions` | 重命名 9.x 函数：不带点的值是 V10 函数名；带点的是完整的订阅名，用于移到了另一个处理器的函数。 |

以下情况检查失败：映射文件所迁移的某个上下文里有未解决执行的处理器没有映射、某个处理器被映射了两次，或者得到的订阅不在 Manifest 中。

每个未解决的执行变成一个停放的条目：

| 停放的条目 | 来自 9.x 执行的 |
| --- | --- |
| 订阅 | 它的函数的映射 |
| 聚合 | 它的聚合所映射的 V10 类型，以及它的 ID 和租户 |
| 起始版本 | 它的事件的版本 |
| 原因 | `<errorCode>: <errorMsg> [9.x execution <id>]`，不含堆栈 |
| 失败时间 | 它最后一次尝试 |
| 重试次数、重试时间 | 它的重试次数，以及最后一次重试的时间 |
| 重试规格 | 它的重试规格 |
| 可恢复 | 它的可恢复标记 |

处理器没有映射、聚合不在映射文件的导入范围内、订阅读的是另一个上下文发布的事件流，或者 V10 日志里没有它的版本，这样的执行标为 `UNRESOLVED`。同一订阅和聚合的两个执行合并为一个条目，从较早的版本开始。

## 检查点 {#checkpoints}

检查点文件（格式 1）保存每一轮进行到哪里：按 9.x 聚合，记录导入、追尾、比对和快照写入；按 `PrepareKey`，记录预留导入；补偿导入；按 V10 聚合类型，记录标签回填。

它旁边的 `<checkpoint>.reservations.jsonl` 在预留每个键之前先记下它，这样之后的导入可以释放 9.x 已放弃的键。把这两个文件放在一起。

多个命令可以同时使用同一个检查点文件，例如 `tail` 与 `compare` 并行：每次改动都持有 `<checkpoint>.lock` 上的锁，并原子地替换文件，所以谁也不会丢失别人的进度。停下的运行最多重复一页。

在工具开始记录来源之前导入的预留键和停放条目，无法与 V10 自己的区分开：导入到全新的 V10 目标，或者在 `import --restart` 之前删除它们。
