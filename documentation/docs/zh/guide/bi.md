---
title: 商业智能
description: Wow 提供实时聚合根状态事件和命令作为商业智能分析的数据源。
---

# 商业智能

## 传统架构 VS 事件溯源

<center>

![事件溯源 VS 传统架构](../../public/images/eventstore/eventsourcing.svg)
</center>

传统实时 ETL 通常要经过 `DB -> CDC -> Process -> DB`。CDC 记录的是数据变化，分析端还需要从变化中还原业务语义。Wow 直接发布带业务语义的命令与状态事件，并生成 ClickHouse 同步及展开 SQL，缩短实时分析链路。

- 聚合命令（`Command`）：用户提交的命令。
- 状态事件（`StateEvent`）：聚合状态的完整变更历史及其关联事件。
- 最新状态事件（`LastStateEvent`）：每个聚合根的最新状态。
- 快照展开视图：将聚合内的一对一和一对多结构展开为关系化视图。

![商业智能](../../public/images/bi/bi.svg)

## 生成与获取 ETL 脚本

### 结构化结果 API

Kotlin 调用方通过 `BiScriptGenerator` 获取 SQL 与诊断：

```kotlin
val result = BiScriptGenerator(
    BiScriptOptions(
        topology = ClickHouseTopology.Standalone,
        consumerGroupNamespace = "orders-production-blue",
        unsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
    )
).generate(aggregates)

val sql: String = result.script
val diagnostics: List<BiScriptDiagnostic> = result.diagnostics
```

公开契约还包含 `BiScriptOperation`、`BiDeploymentInspector`、`BiDeploymentInspection`、`ObservedBiDeployment` 与 `KafkaOffsetStorage`。调用方不再保存或提交 manifest；`Deploy` 根据 ClickHouse catalog 中的 ownership marker 对账，`Reset` 是唯一会删除数据表的操作，并要求明确确认新 Kafka group 会从 earliest 重放。

`BiScriptResult` 包含：

| 字段 | 含义 |
|------|------|
| `script` | 完整 ClickHouse 部署 SQL；`Deploy` 保留数据表，`Reset` 才包含破坏性清理。 |
| `diagnostics` | 按聚合和属性路径稳定排序的不可变诊断列表。 |
| `destructive` | 生成的操作是否删除数据表。 |

每条 `BiScriptDiagnostic` 包含 `code`、`aggregate`、`path`、`sourceType`、`decision` 和 `message`。当前诊断协议只有：

| `code` | `decision` | 含义 |
|--------|------------|------|
| `RAW_JSON_FALLBACK` | `RAW_JSON` | 不支持的属性使用 scoped JSON 查询便利投影和权威 `__state` 恢复。 |
| `MAX_DEPTH_REACHED` | `MAX_DEPTH_RAW_JSON` | 到达最大展开深度时使用相同恢复契约。 |
| `INSPECTION_UNAVAILABLE` | `RECONCILIATION_SKIPPED` | inspector 不可用；只生成当前 desired state，不声称完成旧对象对账。 |
| `ORPHANED_DATA_TABLE` | `DATA_TABLE_RETAINED` | 已移除聚合的 consumer 与视图已退役，但数据表被有意保留。 |
| `CLUSTER_INTERNAL_REPLICATION_REQUIRED` | `EXTERNAL_CONFIGURATION_REQUIRED` | ClickHouse 集群服务配置必须启用 `internal_replication=true`。 |

默认 `unsupportedTypeStrategy` 是 `RAW_JSON`。使用 `FAIL` 时，不支持的属性会立即中止生成，异常消息包含聚合、属性路径和源类型。对象值 Map 使用同一个策略；降级值可通过 `__state` 和当前 recovery path 精确恢复。

### HTTP 路由

Spring WebFlux 路由及其 Swagger/OpenAPI operation 默认启用。当至少一个本地聚合需要生成 Kafka consumer 时，必须配置部署唯一的 `wow.bi.script.consumer-group-namespace`；否则请求返回 `400`，不会阻止应用启动。配置 `wow.bi.script.enabled=false` 可同时移除运行时路由及其 OpenAPI operation，并且不会构造或校验 BI 生成选项和 inspector。该路由使用同一个 `BiScriptOptions`：

```shell
curl -X POST 'http://localhost:8080/wow/bi/script' \
  -H 'content-type: application/json' \
  -H 'accept: application/sql' \
  --data '{}'
```

JSON 请求体必填。`{}` 保持服务端 `BiScriptOptions` 不变；非 `null` 请求字段只在本次生成中覆盖对应的服务端选项。例如，选择独立拓扑并覆盖数据库：

```json
{
  "database": "analytics",
  "topology": {
    "mode": "STANDALONE"
  }
}
```

也可以选择集群拓扑，从服务端基础配置继承省略的集群字段，并覆盖 Kafka 配置：

```json
{
  "topology": {
    "mode": "CLUSTER",
    "cluster": {
      "name": "production"
    }
  },
  "kafkaBootstrapServers": "kafka:9092",
  "topicPrefix": "analytics."
}
```

服务端配置和每个非 `null` 的 `POST` override 使用相同的最大长度：`database` 128 个字符、`consumerDatabase` 128、`timezone` 64、`topicPrefix` 128、`kafkaBootstrapServers` 4096，`topology.cluster.name` 与 `topology.cluster.installation` 各 128。长度恰好等于限制的值可被接受；更长的服务端值会使应用启动失败，更长的请求 override 返回 `400`。`maxExpansionDepth` 单独处理：服务端配置值是 HTTP override 的 ceiling。

提供 `topology` 时必须提供 `topology.mode`。在 `CLUSTER` 模式下，省略的 `cluster` 字段继承当前集群基础配置；如果服务端基础配置是独立模式，则继承领域集群默认值。`STANDALONE` 拒绝 `cluster` 对象。无效或空请求体返回 `400`；缺少或不支持的 `Content-Type` 返回 `415`。响应会遵循 `Accept` 的 quality value；JSON 返回 SQL、诊断与 destructive 标记，SQL 与通配符只返回 `result.script`。请求的表示均不受支持，或所有受支持表示均设为 `q=0` 时返回 `406`。每个成功响应都包含 `Wow-BI-Diagnostic-Count`。生成工作在线程池 `boundedElastic` 上执行。

本版本默认注入 `NoOpBiDeploymentInspector`。它返回显式 `Unavailable`：普通 `DEPLOY` 仅适合首次部署或离线预览，同时会产生 `INSPECTION_UNAVAILABLE` 诊断；它无法清理旧对象，也无法恢复 `RESET` 创建的 consumer identity，配置变化还可能选择新的 consumer group。`RESET` 会被拒绝。需要完整对账时，配置 `wow.bi.script.inspector.type=CLICKHOUSE` 及 `inspector.clickhouse.endpoints`，即可启用 ClickHouse 官方 Java `client-v2` 实现。真实 inspector 查询失败会直接传播错误，不会降级为 NoOp；选择 `CLICKHOUSE` 但缺少 client-v2 类时应用启动失败。自定义 `BiDeploymentInspector` Bean 仍具有最高优先级。

如需有意重建全部 BI 数据，请发送 `operation=RESET` 与 `replayFromEarliestConfirmed=true`。只有可用 inspector 能从 ClickHouse catalog 枚举全部 owned 对象，因此 Reset 会先删除当前及 orphan store，再创建新的 consumer identity。每个生成对象和零行 deployment anchor 都把版本、deployment fingerprint、聚合 owner、对象类型和 identity 写入 `system.tables.comment`；服务重启后直接从 catalog 恢复，不依赖 Wow 服务内存或外部 manifest。

## 生成的 SQL 契约

以下片段只展示稳定结构；实际数据库名、部署拓扑、Kafka 地址、topic 和聚合表名由 `BiScriptOptions` 与聚合元数据决定。

生成的 BI SQL 要求 ClickHouse 24.8 LTS 或更高版本；模块集成测试使用 24.8 镜像固定最低支持线。

### Kafka Offset 生命周期

ClickHouse 24.8 会为新 Kafka consumer group 初始化 `auto.offset.reset=earliest`。运维可以通过服务端 `<kafka><consumer><auto_offset_reset>` 配置覆盖它。首次部署和每次 `Reset` 都必须保持 `earliest`（或 librdkafka 同义值 `smallest`），否则新的 consumer generation 可能跳过已有消息。破坏性 Reset 的 `replayFromEarliestConfirmed=true` 用于确认该外部前置条件；生成的表 DDL 无法检查或覆盖服务端配置。

`KafkaOffsetStorage.KEEPER` 会把 `kafka_keeper_path`、`kafka_replica_name` 作为 Kafka engine settings，并另外生成 CREATE query setting `allow_experimental_kafka_offsets_storage_in_keeper=1`。ClickHouse 24.8 仍将 Keeper offset 存储标记为实验特性，目标 ClickHouse 服务必须配置可访问的 Keeper。

Kafka topic 名保留 context alias 原值。ClickHouse 对象名会把 context alias 中的 `.` 和 `-` 规范化为 `_`；例如 `wow.api.command.order` 使用表前缀 `wow_api_command_order`。如果两个逻辑聚合规范化为相同对象名，生成会直接失败。

### 部署拓扑

`BiScriptOptions.topology` 选择两套物理 DDL 图之一：

执行集群 DDL 前，ClickHouse `remote_servers` 配置中的每个 shard 都必须设置 `internal_replication=true`。该外部服务配置无法由生成 SQL 验证，因此生成器会返回 `CLUSTER_INTERNAL_REPLICATION_REQUIRED` 诊断。

| 拓扑 | 物理表 | 逻辑访问 | DDL 范围 |
|------|--------|----------|----------|
| `ClickHouseTopology.Standalone` | `*_store` 表直接使用 `ReplacingMergeTree` | `command`、`state`、`state_last` 是对存储表执行 `FINAL` 的只读视图 | 不包含 `ON CLUSTER`、复制引擎、`_local` 表和复制路径 |
| `ClickHouseTopology.Cluster(...)` | `*_store_local` 使用复制引擎，`*_store` 提供 `Distributed` 写入门面 | `command`、`state`、`state_last` 仍是只读去重视图 | 数据库、表、物化视图和展开视图都使用 `ON CLUSTER` |

独立模式的命令存储只有一张物理表：

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_command_store"
(
    "id" String,
    "aggregate_id" String,
    "create_time" DateTime64(3, 'Asia/Shanghai')
) ENGINE = ReplacingMergeTree
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id";

CREATE OR REPLACE VIEW "bi_db"."example_order_command"
AS SELECT * FROM "bi_db"."example_order_command_store" FINAL;
```

集群模式保留复制本地表和分布式门面：

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_command_store_local" ON CLUSTER '{cluster}'
(
    "id" String,
    "aggregate_id" String,
    "create_time" DateTime64(3, 'Asia/Shanghai')
) ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id";

CREATE TABLE IF NOT EXISTS "bi_db"."example_order_command_store" ON CLUSTER '{cluster}'
AS "bi_db"."example_order_command_store_local"
ENGINE = Distributed('{cluster}', "bi_db",
                     'example_order_command_store_local', sipHash64("aggregate_id"));

CREATE OR REPLACE VIEW "bi_db"."example_order_command" ON CLUSTER '{cluster}'
AS SELECT * FROM "bi_db"."example_order_command_store" FINAL;
```

### 聚合命令

命令表包含租户、拥有者、空间、请求、版本和命令体等元数据；集群模式的物理表使用上文所示的 `_local` 后缀。Kafka 物化视图从消息 JSON 中提取同名语义：

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_command_store_local" ON CLUSTER '{cluster}'
(
    "id" String,
    "aggregate_id" String,
    "tenant_id" String,
    "owner_id" String,
    "space_id" String,
    "aggregate_version" Nullable(UInt32),
    "body" String,
    "create_time" DateTime64(3, 'Asia/Shanghai')
) ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id";

SELECT JSONExtractString("data", 'ownerId') AS "owner_id",
       JSONExtractString("data", 'spaceId') AS "space_id",
       simpleJSONExtractRaw(
           replaceOne("data",
                      concat('"header":', simpleJSONExtractRaw("data", 'header')),
                      '"header":{}'),
           'body') AS "body";
```

词法提取会先屏蔽顶层 `header` 再读取 `body`，因此 header 中名为 `body` 的嵌套字段不会抢占命令载荷。与完整 JSON 解析再序列化不同，该方式会保留 body token 的原始词法表示。

### 全量状态事件

状态表的 `state` 是完整状态 JSON 字符串，`body` 是事件 JSON 数组，`tags` 保留为结构化 Map。事件视图再按事件顺序展开 `body`：

```sql
"state" String,
"body" Array(String),
"tags" Map(String, Array(String))

JSONExtractArrayRaw("data", 'body') AS "body",
JSONExtract("data", 'tags', 'Map(String, Array(String))') AS "tags"

WITH arrayJoin(arrayZip(arrayEnumerate("body"), "body")) AS "events"
SELECT "events".1 AS "event_sequence",
       JSONExtract("events".2, 'body', 'String') AS "event_body";
```

顶层 `state` 使用相同的 header 屏蔽词法提取，避免 header 中名为 `state` 的嵌套字段替换权威状态 token。

可写状态存储按 `create_time` 月分区，以 `(aggregate_id, version)` 排序。独立模式写入 `example_order_state_store`；集群模式通过分布式 store 写入 `example_order_state_store_local`。`example_order_state` 始终是去重读取视图。

### 最新状态

最新状态存储从状态 store 接收全部列，并按首次事件时间分区。`example_order_state_last` 是始终执行 `FINAL` 的权威公共视图；可写存储统一隔离在 `*_store` 后缀之后。

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_state_last_store"
(
    "aggregate_id" String,
    "version" UInt32,
    "first_event_time" DateTime64(3, 'Asia/Shanghai')
) ENGINE = ReplacingMergeTree("version")
  PARTITION BY toYYYYMM("first_event_time")
  ORDER BY "aggregate_id";

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."example_order_state_last_consumer"
TO "bi_db"."example_order_state_last_store"
AS SELECT * FROM "bi_db"."example_order_state_store";

CREATE OR REPLACE VIEW "bi_db"."example_order_state_last"
AS SELECT * FROM "bi_db"."example_order_state_last_store" FINAL;
```

集群模式保留复制本地表，并让物化视图写入其分布式门面：

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_state_last_store_local" ON CLUSTER '{cluster}'
(
    "aggregate_id" String,
    "version" UInt32,
    "first_event_time" DateTime64(3, 'Asia/Shanghai')
) ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
    '{replica}', "version")
PARTITION BY toYYYYMM("first_event_time")
ORDER BY "aggregate_id";

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."example_order_state_last_consumer"
TO "bi_db"."example_order_state_last_store"
AS SELECT * FROM "bi_db"."example_order_state_store";
```

### 根展开视图

根视图将一对一对象展开为列，并以 `__*` 列继承状态事件元数据。物理输入列统一通过固定表别名 `__source` 限定，避免领域输出别名遮蔽元数据列：

```sql
CREATE VIEW IF NOT EXISTS "bi_db"."example_order_state_last_root" ON CLUSTER '{cluster}' AS
WITH JSONExtractRaw("__source"."state", 'address') AS "address"
SELECT JSONExtract("address", 'city', 'String') AS "address__city",
       JSONExtract("__source"."state", 'id', 'String') AS "id",
       JSONExtractArrayRaw("__source"."state", 'items') AS "items",
       "__source"."state" AS "__state",
       '' AS "__path",
       "__source"."owner_id" AS "__owner_id",
       "__source"."space_id" AS "__space_id",
       "__source"."tags" AS "__tags"
FROM "bi_db"."example_order_state_last" AS "__source";
```

### 子展开视图

对象集合生成子视图；`arrayJoin` 将每个对象元素展开成一行，同时继承父视图列和元数据：

```sql
CREATE VIEW IF NOT EXISTS "bi_db"."example_order_state_last_root_items" ON CLUSTER '{cluster}' AS
WITH arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'items')),
                        JSONExtractArrayRaw("__source"."state", 'items'))) AS "__cursor__items",
     tupleElement("__cursor__items", 2) AS "items"
SELECT JSONExtract("items", 'id', 'String') AS "items__id",
       JSONExtract("items", 'quantity', 'Int32') AS "items__quantity",
       "__source"."state" AS "__state",
       toUInt64(tupleElement("__cursor__items", 1) - 1) AS "__index",
       concat('/items/', toString(tupleElement("__cursor__items", 1) - 1)) AS "__path",
       "__source"."aggregate_id" AS "__aggregate_id",
       "__source"."version" AS "__version"
FROM "bi_db"."example_order_state_last" AS "__source";
```

### 可空类型与原始值

类型由属性的结构化空值信息生成，`Nullable` 可以出现在标量、数组元素和 Map 值层级：

```sql
JSONExtract("__source"."state", 'name', 'Nullable(String)') AS "name",
JSONExtractRaw("__source"."state", 'name') AS "__raw__name",
JSONExtract("__source"."state", 'scores', 'Array(Nullable(Int32))') AS "scores",
JSONExtract("__source"."state", 'ratings', 'Map(String, Nullable(Int32))') AS "ratings"
```

## 结构化类型与无损语义

### 空值传播规则

- 可空标量映射为 `Nullable(T)`。
- 可空集合元素映射为 `Array(Nullable(T))`；可空 Map 值映射为 `Map(String, Nullable(T))`。
- 集合或 Map 属性本身可空时，仍生成类型化列，同时生成一个 `__raw__<target>` 原始伴随列。
- 对象属性本身可空时，其后代类型化列都视为可空，并只在可空祖先处保留一个原始伴随列，不为每个后代重复生成原始列。
- 对象集合的元素可空时，子视图保留当前 `arrayJoin` 元素的 `__raw__<target>` 列，后代列按可空类型提取。
- 整值降级的目标列本身就是 scoped JSON 查询便利值，不再生成重复的 `__raw__*` 伴随列。

Java 未标注的引用类型按可能为空处理；明确的 Kotlin/Java 非空契约保持非空。

### 权威状态恢复通道

每个展开视图都把 `state_last.state` 直接投影为 `__state`。该列是唯一词法权威，ClickHouse 不会对其进行 JSON 解析或重新序列化。根视图的 `__path` 是空 RFC 6901 pointer。集合子视图还提供当前层的零基 `__index` 和完整 `__path`，例如 `/orders/2/lines/5`。属性段中的 `~` 编码为 `~0`，`/` 编码为 `~1`。

消费者需要精确子 token 或子树时，必须按 `__path` 对 `__state` 做 source-slice；JSON parse 后重新序列化不属于词法恢复。

`__raw__` 前缀、`__state`、`__path`、`__index` 以及内部 `__cursor__` 前缀均由生成器保留，领域属性不能占用这些目标。

### Scoped Raw 查询便利值

`__raw__*` 和 fallback 列统一使用 scoped `JSONExtractRaw`。它们适合查询以及区分 missing、显式 null 和空值，但 ClickHouse 可能规范化其中的数字写法，因此绝不是词法权威。

原始列用于区分类型化提取无法区分的状态：

| JSON 输入 | Scoped `JSONExtractRaw` 结果 | 类型化提取 |
|-----------|-------------------------|------------|
| 属性缺失 | 空字符串 `""` | 可空标量为 SQL `NULL`；数组/Map 可能成为空值。 |
| 显式 `null` | 字符串 `"null"` | 可空标量为 SQL `NULL`；数组/Map 可能成为空值。 |
| 空数组 / 空对象 | 字符串 `"[]"` / `"{}"` | 空数组 / 空 Map。 |

这些 scoped 列用于区分结构状态；`__state` 与 `__path` 才是精确恢复通道。

### 不支持的类型

对象值 Map、非 String/可能为空的 Map key、平台对象和无法解析的泛型形状不能直接映射为安全的 ClickHouse 类型。默认策略生成 scoped `JSONExtractRaw` 查询便利列并产生诊断；精确值通过 `__state` 和当前 recovery path 恢复。`FAIL` 策略拒绝生成。到达 `maxExpansionDepth` 时使用相同恢复契约和独立深度诊断。

### 不透明 Jackson 形状

只有配置中的 Wow `JsonSerializer` 能证明声明对象与序列化后的 JSON 对象具有相同属性形状时，才允许递归展开。多态、抽象、sealed、`@JsonValue`、`@JsonUnwrapped`、`@JsonAnyGetter`、自定义序列化、converter 以及其他无法验证的对象都视为不透明。不透明属性只会完整保留为一个原始 JSON 值，或由 `FAIL` 拒绝；不透明根状态会保留完整 `state`。集合和 Map 只有在使用 Jackson 内建容器 serializer，且元素、key、value 映射均验证通过时才生成类型化投影。

### 无损标量映射

标量映射必须同时匹配配置中的 Jackson wire format 和目标 ClickHouse 类型；不匹配时沿用不透明值的 raw/fail 策略。

| JVM 值 | Jackson wire 值 | ClickHouse 投影 |
|--------|-----------------|-----------------|
| `String` | 字符串 | `String` |
| 整数 primitive/boxed 值 | 整数 | 对应的精确有符号整数类型 |
| `Boolean` | Boolean | `Bool` |
| `Char` 与普通字符串 enum | 字符串 | `String` |
| `Float` / `Double` | 数字，或 Jackson 为非有限值生成的字符串 | `Float32` / `Float64` |
| `UUID` | UUID 格式字符串 | `UUID` |
| `Duration`、`Date`、`java.sql.Date`、`Instant` 及其他 `java.time` 值 | ISO/字符串表示 | `String` |
| `Year` | 有符号整数 | `Int32` |
| `BigDecimal` | 任意精度数字 | Scoped `JSONExtractRaw` 查询便利值加权威 `__state` 恢复 |
| Kotlin `Duration` | resolver 提供的 `Long` wire 值 | `Int64` |
| wire format 不是字符串的 enum | 无法验证的标量 | Scoped raw 查询便利值加 `__state` 恢复，或 `FAIL` |
