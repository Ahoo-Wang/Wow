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

`BiScriptResult` 包含：

| 字段 | 含义 |
|------|------|
| `script` | 完整 ClickHouse 部署 SQL，依次包含全局、清理、命令、状态事件、最新状态和展开段。 |
| `diagnostics` | 按聚合和属性路径稳定排序的不可变诊断列表。 |

每条 `BiScriptDiagnostic` 包含 `code`、`aggregate`、`path`、`sourceType`、`decision` 和 `message`。当前诊断协议只有：

| `code` | `decision` | 含义 |
|--------|------------|------|
| `RAW_JSON_FALLBACK` | `RAW_JSON` | 不支持的属性被完整保留为一个原始 JSON 值。 |
| `MAX_DEPTH_REACHED` | `MAX_DEPTH_RAW_JSON` | 到达最大展开深度，整个值以原始 JSON 保留。 |

默认 `unsupportedTypeStrategy` 是 `RAW_JSON`。使用 `FAIL` 时，不支持的属性会立即中止生成，异常消息包含聚合、属性路径和源类型。对象值 Map 使用同一个策略；降级时只保留一个完整原始 JSON 值。

### HTTP 路由

Spring WebFlux 路由使用同一个 `BiScriptOptions`：

```shell
curl -X GET 'http://localhost:8080/wow/bi/script' \
  -H 'accept: application/sql'
```

成功响应固定为 `200`、`Content-Type: application/sql`，响应体只包含 `result.script`。每条诊断以 WARN 日志输出，不会混入 SQL。配置项及优先级参见[配置](./configuration#bi-脚本配置)。

## 生成的 SQL 契约

以下片段只展示稳定结构；实际数据库名、集群、Kafka 地址、topic 和聚合表名由 `BiScriptOptions` 与聚合元数据决定。

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

根视图将一对一对象展开为列，并以 `__*` 列继承状态事件元数据：

```sql
CREATE VIEW IF NOT EXISTS "bi_db"."example_order_state_last_root" ON CLUSTER '{cluster}' AS
WITH JSONExtractRaw("state", 'address') AS "address"
SELECT JSONExtract("address", 'city', 'String') AS "address__city",
       JSONExtract("state", 'id', 'String') AS "id",
       JSONExtractArrayRaw("state", 'items') AS "items",
       "owner_id" AS "__owner_id",
       "space_id" AS "__space_id",
       "tags" AS "__tags"
FROM "bi_db"."example_order_state_last";
```

### 子展开视图

对象集合生成子视图；`arrayJoin` 将每个对象元素展开成一行，同时继承父视图列和元数据：

```sql
CREATE VIEW IF NOT EXISTS "bi_db"."example_order_state_last_root_items" ON CLUSTER '{cluster}' AS
WITH arrayJoin(JSONExtractArrayRaw("state", 'items')) AS "items"
SELECT JSONExtract("items", 'id', 'String') AS "items__id",
       JSONExtract("items", 'quantity', 'Int32') AS "items__quantity",
       "aggregate_id" AS "__aggregate_id",
       "version" AS "__version"
FROM "bi_db"."example_order_state_last";
```

### 可空类型与原始值

类型由属性的结构化空值信息生成，`Nullable` 可以出现在标量、数组元素和 Map 值层级：

```sql
JSONExtract("state", 'name', 'Nullable(String)') AS "name",
JSONExtractRaw("state", 'name') AS "__raw__name",
JSONExtract("state", 'scores', 'Array(Nullable(Int32))') AS "scores",
JSONExtract("state", 'ratings', 'Map(String, Nullable(Int32))') AS "ratings"
```

## 结构化类型与无损语义

### 空值传播规则

- 可空标量映射为 `Nullable(T)`。
- 可空集合元素映射为 `Array(Nullable(T))`；可空 Map 值映射为 `Map(String, Nullable(T))`。
- 集合或 Map 属性本身可空时，仍生成类型化列，同时生成一个 `__raw__<target>` 原始伴随列。
- 对象属性本身可空时，其后代类型化列都视为可空，并只在可空祖先处保留一个原始伴随列，不为每个后代重复生成原始列。
- 对象集合的元素可空时，子视图保留当前 `arrayJoin` 元素的 `__raw__<target>` 列，后代列按可空类型提取。
- 整值降级的目标列本身就是权威原始值，不再生成重复的 `__raw__*` 伴随列。

Java 未标注的引用类型按可能为空处理；明确的 Kotlin/Java 非空契约保持非空。

### `__raw__*` 保留命名空间

`__raw__` 前缀由生成器保留。领域属性序列化名称不能占用该命名空间；与原始伴随列或 `__*` 元数据列冲突时生成会失败，避免静默覆盖。

原始列用于区分类型化提取无法区分的状态：

| JSON 输入 | `JSONExtractRaw` 结果 | 类型化提取 |
|-----------|-------------------------|------------|
| 属性缺失 | 空字符串 `""` | 可空标量为 SQL `NULL`；数组/Map 可能成为空值。 |
| 显式 `null` | 字符串 `"null"` | 可空标量为 SQL `NULL`；数组/Map 可能成为空值。 |
| 空数组 / 空对象 | 字符串 `"[]"` / `"{}"` | 空数组 / 空 Map。 |

因此，可空数组、Map 和对象的 `__raw__*` 列是区分缺失、显式 null 与空复合值的无损通道。

### 不支持的类型

对象值 Map、非 String/可能为空的 Map key、平台对象和无法解析的泛型形状不能直接映射为安全的 ClickHouse 类型。默认策略将整个属性通过 `JSONExtractRaw` 保存在普通目标列并产生诊断；`FAIL` 策略则拒绝生成。到达 `maxExpansionDepth` 时也保存整个原始值，但使用独立的深度诊断。

## 破坏性迁移

### 已移除 API 与配置迁移表

下表是旧名称唯一的兼容说明；本次变更不提供兼容适配层。

| 已移除 | 替代方式 |
|--------|----------|
| `ScriptEngine` / `ScriptTemplateEngine` | `BiScriptGenerator` + `BiScriptOptions` |
| `StateExpansionScriptGenerator` | 由 `BiScriptGenerator` 内部执行结构化规划 |
| `BiScriptRouteOptions` 及路由枚举 | `BiScriptOptions` / `UnsupportedTypeStrategy` |
| WebFlux String/default constructors | 接收 `BiScriptOptions` 的构造函数 |
| `STRING_WITH_DIAGNOSTIC` | `RAW_JSON` |
| `ObjectMapStrategy` / `object-map-strategy` / `STRING_VALUE_WITH_DIAGNOSTIC` | 统一使用 `unsupported-type-strategy`，取值为 `RAW_JSON` / `FAIL` |

### 影响范围

本次变更会改变生成的快照展开视图列类型、可空性和原始伴随列，因此受影响的是生成的展开视图及其下游查询、视图和 BI 数据集。仅因视图类型变化，命令表、状态源表和已有事件数据不需要数据迁移。

`CREATE VIEW IF NOT EXISTS` 不会修改已有视图。升级必须删除并重建生成的展开视图，不能只重新执行创建语句。

### 上线步骤

1. 备份现有展开视图定义，以及受影响的下游查询和数据集定义。
2. 使用新代码和配置生成 SQL，审查类型、`__raw__*` 列、数据库、topic 和诊断。
3. 只删除生成的展开视图，按子视图到父视图的顺序执行；不要运行完整的破坏性 `clear` 段。
4. 按生成顺序重建视图，验证列类型、缺失/null/空值区分和下游查询。
5. 验证通过后再部署依赖这些列的消费者。

### 回滚

按子视图到父视图的顺序删除新展开视图，恢复备份的旧视图定义，再恢复上一版本应用与配置。若下游消费者已经切换到新列，同时回滚其查询或数据集定义。
