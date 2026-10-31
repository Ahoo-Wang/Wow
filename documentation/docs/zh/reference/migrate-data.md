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
| `draft-map` | `--out <file>`、`--force`、`--dry-run` | 根据 9.x 存储里的名字和 V10 的 Manifest 起草映射文件，并把它匹配不上的列为例外：见[起草映射文件](#draft-the-map)。 |
| `check` | | 对照 9.x 存储和 V10 的 Manifest 检查映射文件。不写任何东西。 |
| `import` | `--dry-run`、`--restart` 或 `--final`、`--snapshots` | 先检查，然后导入每个已映射的 9.x 聚合，再导入预留键和未解决的补偿执行，从检查点续做。 |
| `tail` | `--once` | 追踪 9.x 在导入之后提交的内容，直到被停止。 |
| `compare` | `--sample <percent>`、`--restart` | 把每个 9.x 快照与 V10 在快照版本上从日志重建的状态比较，然后比较预留和停放的条目。 |
| `snapshot` | `--restart` | 为每个导入的聚合写入它最后一个版本的 V10 快照。 |
| `backfill-tags` | `--restart`、`--force` | 为每条追加时没有标签的 V10 流记录标签，然后改写没有标签就被索引的 Elasticsearch 事件文档。 |
| `export` | `--dry-run`、`--restart` | 为回滚把 V10 在切换之后写入的流写回 9.x 存储；拒绝带有 V10 独有类型的流。 |

| 选项 | 含义 |
| --- | --- |
| `--config <file>` | 配置文件。每个命令都必须指定。 |
| `--dry-run` | `import` 读取并转换每条流，但不写入，也不保存检查点；`export` 扫描并打印它的结论，但不写入；`draft-map` 打印草稿，不写入。 |
| `--out <file>` | `draft-map` 把草稿写到这里，而不是配置里指定的映射文件。 |
| `--restart` | 忽略上一轮停在哪里，从第一个实例重新遍历每个实例。与 `import` 一起用，就是 9.x 停止写入之后最后那一次完整遍历；它还会对账之前导入的预留键和停放的条目。 |
| `--final` | 9.x 停止写入后 `import` 更短的最后一次遍历，用于追尾一直追到 9.x 停止的情况：从追尾的检查点跑一轮追尾，并往前多读 `final.lookbackMillis`，然后从头重新遍历预留键和补偿执行。如果某个聚合的导入没有完成，或者它的追尾最后一轮结束于 `final.maxTailAgeMillis` 之前，它会拒绝执行，什么都不写；这时运行 `import --restart`。不能与 `--restart` 或 `--dry-run` 一起使用。 |
| `--snapshots` | 在 `import` 之后运行 `snapshot`。 |
| `--once` | `tail` 跑一轮就结束。 |
| `--sample <percent>` | `compare` 只比对这个比例的实例，按租户和 ID 的哈希挑选，所以每次运行比对的都是同一批。换一个比例会重新开始比对。 |
| `--force` | 即使某个日志里仍有没有标签的流，`backfill-tags` 也改写 Elasticsearch 文档，这些流对应的文档保持原样；`draft-map` 替换已有的映射文件。 |

命令不接受的选项属于用法错误。

### 退出码 {#exit-codes}

| 代码 | 含义 |
| --- | --- |
| `0` | 完成。 |
| `1` | 失败：检查未通过、某一步停下了，或者 `import --final` 拒绝执行。 |
| `2` | 用法错误。 |
| `3` | `compare` 发现了差异，在这次运行中，或在它续做的之前的运行中；或者在 `export` 之后，9.x 和 V10 持有的某个聚合的流数量不同。 |

### 输出 {#output}

需要关注的行以一个标记开头：

| 标记 | 含义 |
| --- | --- |
| `PROBLEM` | 检查发现了会阻止导入的问题。 |
| `FAILED` | 某一步停下了，并给出原因；或者 `import --final` 拒绝了某个聚合（它的导入没有完成，或者它的追尾没有一直追到 9.x 停止），并且什么都没写。 |
| `WARNING` | 检查发现了不阻止导入的问题，例如没有被用到的映射。 |
| `DIFFERS` | `compare` 发现了差异：状态、预留或停放的条目。 |
| `UNRESOLVED` | 某个预留键或补偿执行无法安置，没有导入。 |
| `RELEASED` | 之前导入的某个预留被释放了，因为 9.x 不再确认它属于那个所有者。 |
| `RESOLVED` | 之前导入的某个停放条目被标为已解决，因为它对应的 9.x 执行已经解决。 |
| `REFUSED` | `export` 发现一条 9.x 读不了的类型的流：没有任何 9.x 聚合映射到的聚合，或者超出映射文件修订号的事件。不导出任何东西。 |
| `CONFLICT` | `export` 发现一条 9.x 在同一版本上持有、但命令或租户不同的流。不导出任何东西。 |
| `VERDICT` | `export --dry-run` 对一个上下文的结论：切回、导出 N 条流，或者自某个时间起无法通过导出回滚。 |
| `EQUAL`、`DIFFERS` | 在 `export` 之后，9.x 持有的某个聚合的流是否与 V10 一样多。 |
| `UNMATCHED`、`AMBIGUOUS`、`UNSOURCED`、`RENAMED`、`REVIEW` | `draft-map` 的例外，也写进草稿：见[起草映射文件](#draft-the-map)。 |
| `CUTOVER` | 在向某个上下文的日志追加过的一轮之后（`import`、`import --restart`、`import --final`、`tail` 的每一轮），导入的历史现在在哪里结束：切换点，记在每个分区日志的头部。 |

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
| `final.lookbackMillis` | `60000` | `import --final` 在追尾的检查点之前再往回读多远。它覆盖 9.x 各节点的时钟偏差，以及 9.x 停止时仍在途的提交，这些提交的提交时间在写入可见之前就已确定。把它设为实测时钟偏差与 9.x 最慢一次提交两者中较大者的两倍。 |
| `final.maxTailAgeMillis` | `600000` | 追尾的最后一轮必须在多久之内结束，`import --final` 才接受它。 |

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
| `exceptions` | `draft-map` 匹配不上的内容，每行一项，由人解决后删除。检查会把它仍然找到的每一行作为警告打印出来。 |

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

### 起草映射文件 {#draft-the-map}

映射文件的大部分内容，是把 9.x 的 `snake_case` 名字改成 V10 的类名。`draft-map` 根据 9.x 存储里有的名字和 V10 的 Manifest 写出这些条目，写到配置里指定的映射文件，或者 `--out` 指定的文件。没有 `--force` 时，它从不替换已有的文件；`--dry-run` 只打印草稿。

它按确定程度从高到低匹配名字：

1. **9.x 的生命周期。** `default_aggregate_deleted`、`default_aggregate_recovered` 和 `owner_transferred` 变成 `wow.lifecycle.AggregateDeleted`、`AggregateRestored` 和 `OwnerTransferred`，最后一个只用于有所有者的聚合。`default_delete_aggregate` 和 `default_recover_aggregate` 变成 V10 聚合启用的生命周期命令。
2. **完全一致。** 9.x 名字就是 V10 名字、它的某个曾用名、它的完整名字（`example.order.OrderCreated`），或者它的类的限定名。
3. **按单词。** 比较 9.x 名字的最后一段和 V10 名字时忽略大小写、`_`、`-` 和点，所以 `order_created` 和 `order-created` 都匹配 `OrderCreated`。

每一步里，只有一个候选就是匹配，有多个就是有歧义；一个都没有，就进入下一步。匹配到 V10 聚合已退役事件的 9.x 名字，以 `retire` 映射。挂载的命令从不算匹配，因为流里记录的是它被转换成的命令。

9.x 上下文匹配同名的 V10 上下文，或者去掉 `service` 后缀后同名的上下文（`order-service` 就是 `order`），或者 Manifest 里唯一的上下文。9.x 聚合匹配该上下文里同名的 V10 聚合。没有同名的，就匹配事件覆盖了它至少一半事件的那个聚合，前提是只有一个聚合覆盖得最多。

匹配不上的，它都不写进映射，所以在有人补上映射之前，检查会拒绝这份草稿。它把每一处列进草稿的 `exceptions` 并打印出来：

| 标记 | 由人做什么 |
| --- | --- |
| `UNMATCHED` | 为它列出的 9.x 聚合、事件、命令或 `PrepareKey` 写映射。没有任何 V10 名字匹配它。 |
| `AMBIGUOUS` | 在同样匹配的几个 V10 名字中选一个。 |
| `UNSOURCED` | 确认这个 V10 聚合、事件或命令没有 9.x 来源。通常它是 V10 新增的。 |
| `RENAMED` | 确认这个 9.x 聚合变成了它的事件所匹配的那个 V10 聚合。 |
| `REVIEW` | 存储里某个事件有多个修订号、而它们形状不同时，用 `fromRevision` 拆开映射；为没有记录命令的流设置 `defaultCommand`；或者确认映射到 Manifest 唯一上下文的 9.x 上下文。 |

它不写 `headers`、`state`、预留或订阅的映射：这些需要人来写。每一行都解决之后删掉 `exceptions`；在那之前，检查会把每一行作为警告打印出来。

## 导出 {#export}

`export` 反向读取映射文件。每条 V10 流都换回它的 9.x 聚合、事件名和修订号、字段名以及保留的请求头；根命令的 ID、命令 ID、命令的 9.x 名字和操作人的 ID，变成 9.x 的 `trace_id`、`upstream_id`、`upstream_name` 和 `command_operator` 请求头。一条流的 9.x ID 由它的引用和版本推出，每次运行都相同。每个事件得到 9.x 为它的名字和修订号记录的事件体类型，小数写成 `Decimal128`。

它写入 `source` 所指的 9.x 存储，而且只写入带有 9.x 唯一索引 `(aggregateId, version)` 的集合，这个索引由 9.x 启动时创建；没有它，`export` 会停下，并打印创建它的命令。它的检查点是每个 V10 上下文和分区已写入的最后一个日志位置。

## 检查点 {#checkpoints}

检查点文件（格式 1）保存每一轮进行到哪里：按 9.x 聚合，记录导入、追尾及其最后一轮结束的时间、比对和快照写入；按 `PrepareKey`，记录预留导入；补偿导入；按 V10 聚合类型，记录标签回填；按 V10 上下文和分区，记录导出。

它旁边的 `<checkpoint>.reservations.jsonl` 在预留每个键之前先记下它，这样之后的导入可以释放 9.x 已放弃的键。把这两个文件放在一起。

多个命令可以同时使用同一个检查点文件，例如 `tail` 与 `compare` 并行：每次改动都持有 `<checkpoint>.lock` 上的锁，并原子地替换文件，所以谁也不会丢失别人的进度。停下的运行最多重复一页。

切换点保存在 V10 目标里、日志旁边：每个分区一行检查点，组为 `wow.migrate/<context>`，名为 `cutover`，每一轮只会把它往前移，从不后退。服务的订阅把它及之前的条目视为导入的，它的传输 Sink 也把它们标为导入的发布出去（[从 9.x 导入的历史](../guide/published-streams#history-imported-from-9x)）。演练运行（dry run）不记录切换点。

在工具开始记录来源之前导入的预留键和停放条目，无法与 V10 自己的区分开：导入到全新的 V10 目标，或者在 `import --restart` 之前删除它们。
