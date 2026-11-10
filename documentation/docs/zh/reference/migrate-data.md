---
title: migrate-data 命令行
description: migrate-data 工具的命令、选项、退出码、配置文件、迁移映射文件和检查点文件。
outline: deep
---

# migrate-data 命令行

`migrate-data` 把 9.x 服务的数据搬进 V10。本页列出它读取的一切和它做的一切；步骤见[迁移数据](../migrate/data)。

## 运行 {#running}

在你要迁移到的那个 Wow 版本的检出目录里：

```bash
./gradlew :migrate-data:run --args="<command> --config <file> [options]"
```

或者用该版本的镜像 `ahoowang/wow-migrate-data:<version>`（也在 `ghcr.io/ahoo-wang/` 和 `registry.cn-shanghai.aliyuncs.com/ahoo/` 上），它的入口就是命令行：

```bash
docker run --rm -v /path/to/migration:/config:ro -e WOW_PG_PASSWORD \
  ahoowang/wow-migrate-data:<version> <command> --config /config/migrate-data.json [options]
```

它读取 9.x 的 MongoDB 事件存储，写入 PostgreSQL 或 MongoDB 上的 V10 日志。

镜像里只有工具本身，运行在 JDK 25 上。把这些挂载进去：

- 配置文件和映射文件；
- 服务的类（`classpath`）；
- 检查点文件可写的位置，或者把检查点保存在目标里。

它以用户 10001 运行。堆是容器内存上限的 75%，`OutOfMemoryError` 会结束进程（`JAVA_OPTS=-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`）；你自己设置的 `JAVA_OPTS` 会替换它。在 Kubernetes 上，[`deploy/migrate-data`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/migrate-data) 把每个命令作为 Job 运行在存储旁边（[在存储旁边运行](../migrate/data#run-it-next-to-the-stores)）。

`migrate-data --help` 打印用法并以 `0` 退出。

## 命令 {#commands}

| 命令 | 选项 | 做什么 |
| --- | --- | --- |
| `draft-map` | `--out <file>`、`--force`、`--dry-run` | 根据 9.x 存储里的名字和 V10 的 Manifest 起草映射文件，并把它匹配不上的列为例外：见[起草映射文件](#draft-the-map)。 |
| `check` | `--decode` | 对照 9.x 存储和 V10 的 Manifest 检查映射文件。不写任何东西。 |
| `import` | `--dry-run`、`--restart` 或 `--final`、`--snapshots` | 先检查，然后导入每个已映射的 9.x 聚合，再导入预留键和未解决的补偿执行，从检查点续做。 |
| `tail` | `--once` | 追踪 9.x 在导入之后提交的内容，直到被停止。 |
| `compare` | `--sample <percent>`、`--restart` | 把每个 9.x 快照与 V10 在快照版本上从日志重建的状态比较，检查日志持有导入以来、到追尾读到的位置为止 9.x 提交的每一条流，然后比较预留和停放的条目。完整比对完成之后，只再次比对之后两侧有变化的实例和曾有差异的实例，否则完整比对并说明原因（[比对](../migrate/data#_5-compare)）。 |
| `snapshot` | `--restart` | 为每个导入的聚合写入它最后一个版本的 V10 快照，并用状态的访问属性为它的历史盖章；某个 V10 类型的快照全部无失败地写完时，记下它的访问遍历（[访问属性](../migrate/data#_7-the-access-attributes)）。状态无法重建的实例逐个打印出来并继续，遍历完所有聚合后失败；下次运行会重新遍历这些聚合，跳过已写入的快照。一次遍历完成之后，只为 V10 日志之后变化的实例写快照（[写入快照](../migrate/data#_6-write-the-snapshots)）。 |
| `export` | `--dry-run`、`--restart` | 为回滚把 V10 在切换之后写入的流写回 9.x 存储；拒绝带有 V10 独有类型的流。 |

| 选项 | 含义 |
| --- | --- |
| `--config <file>` | 配置文件。每个命令都必须指定。 |
| `--decode` | 映射检查通过后，`check` 用服务的 V10 模型（配置中的 `classpath`）读取已映射聚合的每个 9.x 事件，与服务加载聚合时的读法相同，包括升级函数。每个读不出的事件打印一行 `PROBLEM decode:`，给出它的 9.x 类型和修订版本、所在事件流和原因，然后继续，最后按类型给出计数；只要有一个读不出，检查就失败。存储中缺少导入所需字段的实例事件流也会打印出来，然后继续检查下一个实例。 |
| `--dry-run` | `import` 读取并转换每条流，但不写入，也不保存检查点；`export` 扫描并打印它的结论，但不写入；`draft-map` 打印草稿，不写入。 |
| `--out <file>` | `draft-map` 把草稿写到这里，而不是配置里指定的映射文件。 |
| `--restart` | 忽略上一轮停在哪里，从第一个实例重新遍历每个实例。与 `import` 一起用，就是 9.x 停止写入之后最后那一次完整遍历；它还会对账之前导入的预留键和停放的条目。日志里已有的流按当时的转换结果跳过：导入之后的映射改动只会作用于全新的目标（[迁移数据](../migrate/data#_4-import)）。与 `compare` 一起用时，对导入的流的检查也从每个导入的开始重新读。 |
| `--final` | 9.x 停止写入后 `import` 更短的最后一次遍历，用于追尾一直追到 9.x 停止的情况：从追尾的检查点跑一轮追尾，并往前多读 `final.lookbackMillis`，然后从头重新遍历预留键和补偿执行。如果某个聚合的导入没有完成，或者它的追尾最后一轮结束于 `final.maxTailAgeMillis` 之前，它会拒绝执行，什么都不写；这时运行 `import --restart`。不能与 `--restart` 或 `--dry-run` 一起使用。 |
| `--snapshots` | 在 `import` 之后运行 `snapshot`。 |
| `--once` | `tail` 跑一轮就结束。 |
| `--sample <percent>` | `compare` 只比对这个比例的实例，按租户和 ID 的哈希挑选，所以每次运行比对的都是同一批。换一个比例会重新开始比对。 |
| `--force` | `draft-map` 替换已有的映射文件。 |

命令不接受的选项属于用法错误。

### 退出码 {#exit-codes}

| 代码 | 含义 |
| --- | --- |
| `0` | 完成。 |
| `1` | 失败：检查未通过、某一步停下了，或者 `import --final` 拒绝执行。 |
| `2` | 用法错误。 |
| `3` | `compare` 发现了差异，在这次运行中，或在它续做的之前的运行中，或者发现了日志没有持有的一条 9.x 提交的流；或者在 `export` 之后，9.x 和 V10 持有的某个聚合的流数量不同。 |

### 输出 {#output}

需要关注的行以一个标记开头：

| 标记 | 含义 |
| --- | --- |
| `PROBLEM` | 检查发现了会阻止导入的问题。 |
| `FAILED` | 某一步停下了，并给出原因；或者 `import --final` 拒绝了某个聚合（它的导入没有完成，或者它的追尾没有一直追到 9.x 停止），并且什么都没写；或者 `snapshot` 无法写入某个实例的快照。 |
| `WARNING` | 检查发现了不阻止导入的问题，例如没有被用到的映射。 |
| `DIFFERS` | `compare` 发现了差异：状态、预留或停放的条目。 |
| `MISSING` | `compare` 发现一条导入以来、在追尾读到的位置之前 9.x 提交的流，日志没有在它的版本上以它的命令持有它。 |
| `UNRESOLVED` | 某个预留键或补偿执行无法安置，没有导入。 |
| `RELEASED` | 之前导入的某个预留被释放了，因为 9.x 不再确认它属于那个所有者。 |
| `RESOLVED` | 之前导入的某个停放条目被标为已解决，因为它对应的 9.x 执行已经解决。 |
| `REFUSED` | `export` 发现一条 9.x 读不了的类型的流：没有任何 9.x 聚合映射到的聚合，或者超出映射文件修订号的事件。不导出任何东西。 |
| `CONFLICT` | `export` 发现一条 9.x 在同一版本上持有、但命令或租户不同的流。不导出任何东西。 |
| `VERDICT` | `export` 对一个上下文的结论，每次运行都会打印（包括 `--dry-run`）：切回、导出 N 条流、自某个时间起无法通过导出回滚，或者因为 9.x 在切换之后被写入而无法通过导出回滚。 |
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
| `manifests` | 来自 classpath | 流要去往的各服务合并后的 V10 Manifest。不写时，工具按每个 `classpath` 条目，从其中服务模块的片段组装该服务的 Manifest，与服务的节点启动时所做的相同。映射导入的某个上下文不在任何条目里时失败，并给出这些上下文。 |
| `checkpoint` | `migrate-data.checkpoint.json` | 检查点文件；或者 `target:<name>`，把检查点以该名字保存在 V10 目标里（名字由字母、数字、`.`、`_` 和 `-` 组成，最多 128 个字符）：见[检查点](#checkpoints)。 |
| `source.uri`、`source.database` | 必填 | 9.x 的 MongoDB 事件存储。数据库中没有任何事件流时，`draft-map` 和 `check` 失败并给出该数据库的名字：名字拼错，或 `env:` 引用的变量未设置，读到的都是一个空存储。 |
| `source.createCommitTimeIndex` | `false` | 允许 `tail` 在每个事件流集合上创建它需要的 `(createTime, _id)` 索引，这会写入 9.x 存储。否则没有索引时它拒绝启动，并打印创建命令。`check` 会对每个缺少该索引的已映射集合发出警告，但不创建任何索引。 |
| `target.type` | 必填 | `postgres` 或 `mongo`。 |
| `target.jdbcUrl`、`target.username`、`target.password` | | PostgreSQL 目标。 |
| `target.poolSize` | `16` | 连接 PostgreSQL 目标的最大连接数。 |
| `target.uri`、`target.database` | | MongoDB 目标，必须是副本集。 |
| `partitions` | | 每个 V10 上下文的分区数，必须与服务自己的相同。 |
| `classpath` | 工具自己的类路径 | 服务的 V10 领域模块，每项是一个 jar，或者一个包含类或 jar 的目录，例如服务发行包的 `lib`。`compare` 和 `snapshot` 用它们的模型重建状态，`check --decode` 用它们读取 9.x 事件，不写 `manifests` 时 Manifest 也来自它们。工具不运行任何决定函数，所以模型需要的依赖中，接口用替身代替，`java.time.Clock` 或 `java.time.ZoneId` 给系统的 UTC 时钟或时区。模型需要其他类的依赖时失败并给出类名：在模型中改为接收接口。 |
| `compensation.database` | | 9.x 补偿服务的数据库。不配置 `compensation` 时，不读取补偿服务的任何内容。 |
| `compensation.uri` | 与来源相同 | 该数据库所在的服务器。 |
| `compensation.service` | | 接收未解决执行的 V10 服务，其失败台账会收到它们：填它的 `wow.service` 或 `spring.application.name`。 |
| `pageSize` | `500` | 两个检查点之间导入的实例数，这些实例连同它们的流在一次扫描中读出，每页最多按每个实例十条流计：实例较大时一页的实例更少。也是追尾一次读取的流数。 |
| `streamBatch` | `200` | 一页只读到一部分的实例，其余的流每次从 9.x 存储读取的条数。 |
| `concurrency` | `8` | 全量导入的一页同时分成多少组追加：每组由完整的实例组成，是对日志的一次调用（组提交），最多 1000 条流。也是追尾同时导入的实例数。组数更多需要更大的 `target.poolSize`，但在 50 万订单的基准中，16 组和 32 组都比 8 组慢：每次组提交在 PostgreSQL 上等得更久。 |
| `readParallelism` | `2` | 全量导入同时遍历的 id 区间数，每个区间是对 9.x 存储的一次独立扫描。区间在导入首次开始时按从存储中采样的 id 划分，并保留到导入完成。每多一个区间，9.x 在服务生产的同时就多一个扫描。在 50 万订单的基准中，2 个区间比 1 个快 7% 到 16%，8 个区间再快 3% 到 4%；9.x 的 MongoDB 用了 0.47 个 CPU 核（1 个区间时 0.44），操作速率相同；一个像 9.x 处理命令那样读取单个订单的流的探测查询，其 p99 从 1 个区间时的 13–22 ms 升到 2 个区间时的 47–70 ms、8 个区间时的 87–103 ms（存储和工具共用一台机器）。双跑期间保持 2，9.x 的延迟预算紧张时用 1；9.x 停止后可到 8（[导入](../migrate/data#_4-import)）。 |
| `lookbackMillis` | `300000` | 追尾在它读到的最后提交时间之前，再往回扫描多远。每一轮读到它开始时最新提交的那条流为止，所以一条流在提交时间之后这个窗口内变为可见，就会被读到：把它设得大于 9.x 各节点的时钟偏差加上 9.x 最慢的一次提交。 |
| `pollIntervalMillis` | `1000` | 两轮追尾之间的间隔。 |
| `final.lookbackMillis` | `60000` | `import --final` 在追尾的检查点之前再往回读多远。它覆盖 9.x 各节点的时钟偏差，以及 9.x 停止时仍在途的提交，这些提交的提交时间在写入可见之前就已确定。把它设为实测时钟偏差与 9.x 最慢一次提交两者中较大者的两倍。 |
| `final.maxTailAgeMillis` | `600000` | 追尾的最后一轮必须在多久之内结束，`import --final` 才接受它。 |

如果一个服务的各聚合把日志放在不同的 V10 存储里，就按存储分别运行一次，每次使用一份只含该存储所存聚合的映射文件，并排除其余聚合（`excluded`）。

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
        { "from": "pay_order", "to": "PayOrder" },
        { "from": "default_apply_resource_tags", "to": "TagOrder" }
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
        { "from": "default_resource_tags_applied", "to": "OrderTagged", "tags": true },
        { "from": "order_archived", "to": "OrderArchived", "retire": true },
        { "from": "order_noted", "drop": true }
      ],
      "state": {
        "fields": { "orderStatus": "status" },
        "ignore": ["payable"]
      },
      "reservations": [
        { "from": "order_no", "namespace": "order.no", "tenancy": "SYSTEM", "ownerId": "orderId" }
      ],
      "tags": [
        { "key": "region", "to": "region" },
        { "key": "team", "to": "teams" },
        { "key": "channel", "to": "channel", "source": "DERIVED" },
        { "key": "legacy", "reason": "No access policy reads it: 9.x set it for a report retired in 2024" }
      ]
    }
  ],
  "subscriptions": [
    { "from": "order-service.OrderSaga", "functions": { "onEvent": "onOrderCreated" } },
    { "from": "order-service.OrderProjector", "to": "OrderReports" }
  ],
  "excluded": [
    { "aggregate": "order-service.audit", "reason": "The audit service's V10 store takes it" },
    { "aggregate": "order-service.order", "stream": "(empty)", "reason": "A 9.x defect: an order stored without an id" }
  ]
}
```

不带点的 V10 名字是聚合命名空间里的简单名（`OrderCreated` 即 `example.order.OrderCreated`）；带点的是完整名，例如 `wow.lifecycle.OwnerTransferred`。

### 顶层 {#top-level}

| 属性 | 含义 |
| --- | --- |
| `format` | `1`。 |
| `aggregates` | 每个 9.x 聚合一项；见下文。 |
| `headers` | 作为请求事实保留的 9.x 流请求头，9.x 键对应 V10 名字。其他请求头都被丢弃。9.x 框架请求头（`trace_id`、`upstream_id`、`upstream_name`、`command_operator`、`local_first`、等待请求头）不能列出：它们各自变成单独的字段，或被丢弃。较早的 9.x 用点号写这些请求头（`trace.id`、`upstream.id`、`upstream.name`、`command.operator`、`command.wait.*`），读作同样的请求头；它的一些流只用 `command.name` 记录命令名。 |
| `subscriptions` | 有未解决补偿执行的 9.x 处理器，映射到 V10 订阅；见下文。 |
| `exceptions` | `draft-map` 匹配不上的内容，每行一项，由人解决后删除。检查会把它仍然找到的每一行作为警告打印出来。 |
| `excluded` | 映射文件有意不迁移的 9.x 聚合（`aggregate`，`contextName.aggregateName`）和 `PrepareKey`（`prepareKey`），每项都要给出 `reason`（必填）。同时写有 `aggregate` 和 `stream` 的项只排除该聚合的一个实例的事件流，以其 9.x 聚合 ID 命名，覆盖所有租户：用于 V10 无法接收的实例，例如 ID 被 V10 拒绝的实例（见下文）。检查把每一项连同原因作为警告打印，而不是失败；既被映射又被排除、或被排除两次的项会使检查失败，存储中没有的项是警告，所属聚合未被映射文件导入的实例事件流也是警告。`import`、`snapshot` 和 `compare` 都不读取其中任何一项；被排除的实例什么都不导入：既不导入它的事件流，也不导入它拥有的预留键或它上面的补偿执行，每一项都以 `UNRESOLVED` 打印，并给出该项的原因。`draft-map` 从不写入它。 |

V10 拒绝一些 9.x 曾接受的 ID：空的聚合 ID（或根本没有）、空白 ID，以及 `(none)`，即 V10 给尚未由准入分配聚合 ID 的请求所用的 ID。带有这类 ID 的实例无法导入，属于 9.x 的数据缺陷。检查会按聚合各报一条 `PROBLEM`，列出每个这样的 ID、其事件流数量和租户，并写成 `excluded` 项用来命名它的 JSON 字符串：空 ID 写作 `"(empty)"`，便于复制。没有记录租户（或租户为空白）的事件流同样被 V10 拒绝，会以单独的 `PROBLEM` 按聚合 ID 列出。先确定每个实例的事件流是什么，再排除它，如示例中的 `{ "aggregate": "order-service.order", "stream": "(empty)", "reason": "…" }`。

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
| `tags` | 聚合的标签事件写过、或它的快照持有的每个 9.x 标签键，以及它变成的 V10 访问属性；见下文。存储里的每个键都需要一项。 |

### 事件 {#events}

| 属性 | 含义 |
| --- | --- |
| `from` | 9.x 事件名。 |
| `fromRevision` | 这一项映射的 9.x 修订号；不写时匹配任意修订号。 |
| `to` | V10 事件名；对退役事件，是退役的名字。 |
| `toRevision` | 9.x 载荷在 V10 中对应的修订号，默认 1：9.x 的形状就是修订号 1，V10 的升级函数把它一路读到当前修订号。不能超过 Manifest 中的修订号。 |
| `fields` | 重命名载荷的顶层字段，9.x 名字对应 V10 名字。用于形状由 V10 固定的系统事件，例如 9.x 的 `OwnerTransferred.toOwnerId`；领域事件的变化应放在它的升级函数里。 |
| `upgrade` | Skill 写的升级函数，记录下来供报告使用；工具不运行它。 |
| `retire` | 把记录按原样以 `to` 这个名字写入，V10 聚合把这个名字列为退役，所以加载时跳过它，流也保留它的版本。`to` 在 Manifest 中没有退役时，检查失败。只用于没有改变状态的事件：退役一个删除或状态变化会把它丢掉（[代码已删除的历史](../migrate/data#_2-check-the-map)）。 |
| `lifecycle` | `{to, fields}`：紧接着这个事件、在同一条流中写入的系统生命周期事件，用于实现了某个生命周期事件的 9.x 领域事件（`UserDeleted : AggregateDeleted`）。`to` 是 `wow.lifecycle.AggregateDeleted`、`AggregateRestored`、`OwnerTransferred` 或 `SpaceTransferred`；`fields` 从 9.x 载荷构造它的载荷，9.x 名字对应 V10 名字，后两者要给出 `ownerId` 或 `spaceId`。9.x 的 `ResourceTagsApplied` 没有对应的生命周期事件：用 `tags` 映射它。不能与 `drop` 或 `retire` 一起用。 |
| `drop` | 把这个事件从它的流中去掉。如果一条流的事件全被丢弃，这个版本就是空的，检查会拒绝：这样的事件要用 `retire` 映射。 |
| `tags` | 用于 9.x 的标签事件（`default_resource_tags_applied`，或实现了 `ResourceTagsApplied` 的领域事件）：它的 `tags` 变成 V10 事件 `to` 的字段，每个映射为 `APPLIED` 的键一个，以它的访问属性命名，每个值按该属性的 Manifest 条目转换。每个这样的字段都会写入，缺失的键写作 `null` 或 `[]`，就像 9.x 替换整个标签映射那样；载荷的其他字段保留，并按 `fields` 重命名。不能与 `drop` 或 `retire` 一起用。 |

### 标签 {#tags}

9.x 以两种方式给聚合打标签：它的标签命令（`default_apply_resource_tags`）设置标签，其事件（`default_resource_tags_applied`）替换整个标签映射；实现了 `StateAggregateTagsExtractor` 的状态在每次提交时推出标签，9.x 保留它返回的结果：合并已有标签的提取器（9.x 示例中的就是这样）会保留自上一个标签事件以来打上或推出过的每一个值。V10 没有标签：每个键变成状态的一个访问属性（`@Access`），即某个领域事件设置的字段，或者状态的一个函数（[标签与基于属性的访问](../migrate/source#tags-and-attribute-based-access)、[声明访问属性](../guide/ownership#declare-access-attributes)）。

| 属性 | 含义 |
| --- | --- |
| `key` | 9.x 标签键。 |
| `to` | V10 访问属性，按聚合的 Manifest 声明它的名字。不写时，这个键不迁移。 |
| `source` | 它的值从哪里来。`APPLIED`（默认）：标签事件设置它，映射的标签事件写入状态字段。`DERIVED`：9.x 的状态推出它，V10 用一个 `@Access` 函数推出它。`BOTH`：既被设置又被推出，像在 9.x 中那样由提取器说了算，所以由 V10 推出它，标签事件略去它设置的值。 |
| `reason` | 没有 `to` 时必填：为什么不迁移这个键。略去一个键会改变谁能读取数据，所以映射文件记下原因，检查把它作为警告打印出来。 |

值按属性的 Manifest 条目转换：多值属性持有这个键去重后的值，单值属性持有它唯一的值或 `null`；`INTEGER` 属性把每个值读作整数，`BOOLEAN` 属性读取 `true` 或 `false`，不区分大小写。

以下情况检查失败：

- 标签事件写过、或快照持有的某个键没有映射，或者映射指向 V10 聚合没有声明的属性；
- 某个键由标签事件写过、却映射为 `DERIVED`，或者只有快照持有、却映射为 `APPLIED`；
- 标签事件为某个 `APPLIED` 键写入了它的属性容纳不了的值：单值属性有两个或更多的值，整数或布尔属性的值不是整数或布尔值。问题给出它们的计数，并列出至多三条持有它们的流；
- 标签事件的映射没有 `"tags": true`，或者它写入了已映射的键、却被丢弃。

它对以下情况发出警告：不迁移的键，并引述它的原因；映射为 `BOTH` 的键；存储里没有的已映射键；载荷含有 `tags` 对象的其他事件；以及快照持有推出键的属性容纳不了的值，并列出至多三个实例。导入不写入这些值，但 `compare` 会报告它们：9.x 把它的状态推出的值并入它保存的标签，所以快照里可能在一个键的当前值旁边还留着它以前的值。

`compare` 把键映射到的每个属性记作 `$access.<attribute>` 比较：9.x 快照的标签按导入的方式转换，与 V10 从重建的状态推出的属性比较，两侧以同样的方式规范化，所以多值属性的值的顺序从不构成差异。状态比较把这个属性从状态中略去。状态字段与它的属性不同名时（`@Access("org") val orgPath`），把这个字段列进 `state.ignore`。

```text
DIFFERS example-service.order (0)/0VY3GwaK4OmV00E v6: $access.region: 9.x "west", V10 "east"
```

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

切换之前 9.x 会继续重试执行。从第一个执行开始的遍历（`import --final`、`import --restart`）把导入为某个执行停放的条目更新为 9.x 现在持有的样子：上表中的原因、失败时间和重试状态，前提是 V10 在读到这个条目之后没有再写过它。V10 自己停放或再次停放的条目从不改动，另一个较早的执行停放的条目也不改动。`compare` 把落后于其执行的导入条目报告为 `DIFFERS`。

### 起草映射文件 {#draft-the-map}

映射文件的大部分内容，是把 9.x 的 `snake_case` 名字改成 V10 的类名。`draft-map` 根据 9.x 存储里有的名字和 V10 的 Manifest 写出这些条目，写到配置里指定的映射文件，或者 `--out` 指定的文件。没有 `--force` 时，它从不替换已有的文件；`--dry-run` 只打印草稿。

它按确定程度从高到低匹配名字：

1. **9.x 的生命周期。** `default_aggregate_deleted`、`default_aggregate_recovered` 和 `owner_transferred` 变成 `wow.lifecycle.AggregateDeleted`、`AggregateRestored` 和 `OwnerTransferred`，最后一个只用于有所有者的聚合。`default_delete_aggregate` 和 `default_recover_aggregate` 变成 V10 聚合启用的生命周期命令。9.x 的标签事件 `default_resource_tags_applied` 以 `"tags": true` 变成 V10 聚合里名字含有 tag 一词的那个唯一事件（`OrderTagged`、`TagsAssigned`），标签命令 `default_apply_resource_tags` 变成名字含有这个词的那条唯一命令（`TagOrder`）；没有这样的事件或命令时，草稿写上 `<Aggregate>Tagged` 和 `Tag<Aggregate>`，并标为 `REVIEW`。它按每个键的来源起草 `tags`：标签事件写过的键为 `APPLIED`，只有快照持有的键为 `DERIVED`，各自映射到单词相同的访问属性，或者标为 `UNMATCHED`。
2. **完全一致。** 9.x 名字就是 V10 名字、它的某个曾用名、它的完整名字（`example.order.OrderCreated`），或者它的类的限定名。
3. **按单词。** 比较 9.x 名字的最后一段和 V10 名字时忽略大小写、`_`、`-` 和点，所以 `order_created` 和 `order-created` 都匹配 `OrderCreated`。

每一步里，只有一个候选就是匹配，有多个就是有歧义；一个都没有，就进入下一步。匹配到 V10 聚合已退役事件的 9.x 名字，以 `retire` 映射。挂载的命令从不算匹配，因为流里记录的是它被转换成的命令。

9.x 上下文匹配同名的 V10 上下文，或者去掉 `service` 后缀后同名的上下文（`order-service` 就是 `order`），或者 Manifest 里唯一的上下文。9.x 聚合匹配该上下文里同名的 V10 聚合。没有同名的，就匹配事件覆盖了它至少一半事件的那个聚合，前提是只有一个聚合覆盖得最多。

匹配不上的，它都不写进映射，所以在有人补上映射之前，检查会拒绝这份草稿。它把每一处列进草稿的 `exceptions` 并打印出来：

| 标记 | 由人做什么 |
| --- | --- |
| `UNMATCHED` | 为它列出的 9.x 聚合、事件、命令、`PrepareKey` 或标签键写映射。没有任何 V10 名字匹配它。 |
| `AMBIGUOUS` | 在同样匹配的几个 V10 名字中选一个。 |
| `UNSOURCED` | 确认这个 V10 聚合、事件或命令没有 9.x 来源。通常它是 V10 新增的。 |
| `RENAMED` | 确认这个 9.x 聚合变成了它的事件所匹配的那个 V10 聚合。 |
| `REVIEW` | 存储里某个事件有多个修订号、而它们形状不同时，用 `fromRevision` 拆开映射；为没有记录命令的流设置 `defaultCommand`；或者确认映射到 Manifest 唯一上下文的 9.x 上下文。 |

它不写 `headers`、`state`、预留或订阅的映射：这些需要人来写。每一行都解决之后删掉 `exceptions`；在那之前，检查会把每一行作为警告打印出来。

## 导出 {#export}

`export` 反向读取映射文件。每条 V10 流都换回它的 9.x 聚合、事件名和修订号、字段名以及保留的请求头，打标签事件换回它的 `tags`：它设置的每个属性放回它的 9.x 键下，值写成字符串，没有值的属性不写这个键；根命令的 ID、命令 ID、命令的 9.x 名字和操作人的 ID，变成 9.x 的 `trace_id`、`upstream_id`、`upstream_name` 和 `command_operator` 请求头。一条流的 9.x ID 由它的引用和版本推出，每次运行都相同。每个事件得到 9.x 为它的名字和修订号记录的事件体类型，小数写成 `Decimal128`。

它写入 `source` 所指的 9.x 存储，而且只写入带有 9.x 唯一索引 `(aggregateId, version)` 的集合，这个索引由 9.x 启动时创建；没有它，`export` 会停下，并打印创建它的命令。它的检查点是每个 V10 上下文和分区已写入的最后一个日志位置。

## 检查点 {#checkpoints}

检查点（格式 1）保存每一轮进行到哪里：

- 按 9.x 聚合：导入、追尾及其最后一轮结束的时间、比对、快照写入，以及 `compare` 确认 9.x 提交的每一条流都在日志里的位置；比对和快照写入还记录各自开始时 V10 日志的末尾，比对还记录它的指纹和存在差异的实例（最多 1000 个），`compare` 和 `snapshot` 从那里继续；
- 按 `PrepareKey`：预留导入；
- 补偿导入；
- 按 V10 上下文和分区：导出。

导入还会在预留每个键之前先记下它，这样之后的导入可以释放 9.x 已放弃的键。停下的运行最多重复它当时在处理的几页：正在追加的两页，以及预先读取的一页。

**保存在文件里**，这是默认方式。检查点文件旁边是 `<checkpoint>.reservations.jsonl`，即预留的键。把这两个文件放在一起。每次改动都持有 `<checkpoint>.lock` 上的锁，并原子地替换文件。

**保存在目标里**，即 `"checkpoint": "target:<name>"`。在另一台机器上的运行，或重启后的 Pod，都能在日志旁边找到它们：

- 每个名字的检查点是 `wow_migrate_checkpoint` 里的一行（PostgreSQL 的表，或同名的 MongoDB 集合），内容与文件相同；
- 预留的键是 `wow_migrate_imported_key` 里的行；
- 每次改动都是对所存版本的比较并交换，冲突时重试。

### 每类运行同时只有一个 {#one-run-of-each-kind-at-a-time}

写检查点的运行在结束之前一直持有其命令的角色：

| 角色 | 命令 |
| --- | --- |
| `import` | `import`、`import --final`、`tail`、`export` |
| `compare` | `compare` |
| `snapshot` | `snapshot`、`import --snapshots` |
| `export` | `export` |

`export` 同时持有 `import` 角色，因为它写入导入和追尾所读的 9.x 存储。不同角色的运行共用检查点，各写各的条目，例如 `tail` 与 `compare` 并行。`check`、`draft-map` 和演练运行（dry run）不持有角色。

- **检查点文件。** 每个角色是 `<checkpoint>.<role>.lock` 上的一把操作系统锁，进程无论怎样结束，系统都会释放它。发现锁被持有的运行立即失败，并给出锁文件记下的那次运行。
- **保存在目标里的检查点。** 每个角色是目标自己的租约存储里的一份租约（`wow_lease`，组为 `wow.migrate.checkpoint/<name>`）：30 秒，每 10 秒续约一次。没有释放租约就停下的运行不再续约，它的租约就会过期。发现角色被持有的运行最多等一个租约期，等它结束后接手。持有者一直续约时，这次运行失败并给出持有者：

  ```text
  FAILED The checkpoint target:order-service is in use: tail on migrate-data-tail-5c7d9 (pid 1, run 019a…) holds its import lease, renewed while it runs (now until 2026-11-03T10:15:30Z). One import, import --final, tail or export runs on a checkpoint at a time: stop that run, or wait for it to end.
  ```

  每次写入都带着这次运行各租约的 epoch。设想一次运行暂停的时间超过了租约期，另一次运行接手租约并写入。前一次运行的下一次写入就会失败，即使它还不知道自己已经失去租约，然后它不写任何东西就停下：

  ```text
  FAILED This run lost its import lease of the checkpoint target:order-service to another run, which has written the checkpoint since (lease epoch 2, this run's 1). It stops without writing.
  ```

切换点保存在 V10 目标里、日志旁边：每个分区一行检查点，组为 `wow.migrate/<context>`，名为 `cutover`，每一轮只会把它往前移，从不后退。服务的订阅把它及之前的条目视为导入的，它的传输 Sink 也把它们标为导入的发布出去（[从 9.x 导入的历史](../guide/published-streams#history-imported-from-9x)）。演练运行（dry run）不记录切换点。

在工具开始记录来源之前导入的预留键和停放条目，无法与 V10 自己的区分开：导入到全新的 V10 目标，或者在 `import --restart` 之前删除它们。
