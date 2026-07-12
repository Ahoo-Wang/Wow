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
        unsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
    )
).generate(aggregates)

val sql: String = result.script
val diagnostics: List<BiScriptDiagnostic> = result.diagnostics
```

公开契约只有七个类型：`BiScriptGenerator`、`BiScriptOptions`、`UnsupportedTypeStrategy`、`BiScriptResult`、`BiScriptDiagnostic`、`BiScriptDiagnosticCode` 和 `BiScriptMappingDecision`。规划、渲染、类型映射与 executable statement 模型均为内部实现。

`BiScriptResult` 包含：

| 字段 | 含义 |
|------|------|
| `script` | 完整 ClickHouse 部署 SQL，依次包含全局、清理、命令、状态事件、最新状态和展开段。 |
| `diagnostics` | 按聚合和属性路径稳定排序的不可变诊断列表。 |

每条 `BiScriptDiagnostic` 包含 `code`、`aggregate`、`path`、`sourceType`、`decision` 和 `message`。当前诊断协议只有：

| `code` | `decision` | 含义 |
|--------|------------|------|
| `RAW_JSON_FALLBACK` | `RAW_JSON` | 不支持的属性使用 scoped JSON 查询便利投影和权威 `__state` 恢复。 |
| `MAX_DEPTH_REACHED` | `MAX_DEPTH_RAW_JSON` | 到达最大展开深度时使用相同恢复契约。 |

默认 `unsupportedTypeStrategy` 是 `RAW_JSON`。使用 `FAIL` 时，不支持的属性会立即中止生成，异常消息包含聚合、属性路径和源类型。对象值 Map 使用同一个策略；降级值可通过 `__state` 和当前 recovery path 精确恢复。

### HTTP 路由

Spring WebFlux 路由使用同一个 `BiScriptOptions`：

```shell
curl -X GET 'http://localhost:8080/wow/bi/script' \
  -H 'accept: application/sql'
```

成功响应固定为 `200`、`Content-Type: application/sql`，响应体只包含 `result.script`。每条诊断以 WARN 日志输出，不会混入 SQL。配置项及优先级参见[配置](./configuration#bi-脚本配置)。

## 生成的 SQL 契约

以下片段只展示稳定结构；实际数据库名、集群、Kafka 地址、topic 和聚合表名由 `BiScriptOptions` 与聚合元数据决定。

生成的 BI SQL 要求 ClickHouse 24.8 LTS 或更高版本；模块集成测试使用 24.8 镜像固定最低支持线。

### 聚合命令

命令本地表包含租户、拥有者、空间、请求、版本和命令体等元数据，Kafka 物化视图从消息 JSON 中提取同名语义：

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_command_local" ON CLUSTER '{cluster}'
(
    "id" String,
    "aggregate_id" String,
    "tenant_id" String,
    "owner_id" String,
    "space_id" String,
    "aggregate_version" Nullable(UInt32),
    "body" String,
    "create_time" DateTime('Asia/Shanghai')
) ENGINE = ReplicatedMergeTree(
    '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id";

SELECT JSONExtractString("data", 'ownerId') AS "owner_id",
       JSONExtractString("data", 'spaceId') AS "space_id",
       JSONExtractString("data", 'body') AS "body";
```

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

状态本地表按 `create_time` 月分区，以 `(aggregate_id, version)` 排序。

### 最新状态

最新状态表从状态分布式表接收全部列，并按首次事件时间分区：

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_state_last_local" ON CLUSTER '{cluster}'
(
    "aggregate_id" String,
    "version" UInt32,
    "first_event_time" DateTime('Asia/Shanghai')
) ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
    '{replica}', "version")
PARTITION BY toYYYYMM("first_event_time")
ORDER BY "aggregate_id";

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."example_order_state_last_consumer"
TO "bi_db"."example_order_state_last"
AS SELECT * FROM "bi_db"."example_order_state";
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
