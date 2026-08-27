# 聚合 ANY 代表值指标设计

## 背景

Wow 的 `AggregationQuery` 已支持通过 `TERMS` 按精确值分组，以及 `COUNT`、`SUM`、`AVG`、`MIN`、`MAX` 指标。当前查询可以按 `productId` 分组，但不能在不改变分组键的前提下返回冗余的 `productName`。把 `productName` 也声明为 `TERMS` 会形成 `(productId, productName)` 联合分组；同一 ID 下名称不一致时会拆成多行。

本设计增加类似 ClickHouse `any` 的代表值指标：`productId` 继续决定分组，`productName` 只从组内选择一个非空标量用于展示。

## 目标

- 新增独立的 `AggregationMetric.Any` 公共模型和 JSON subtype `ANY`。
- Kotlin DSL 支持 `any(field, alias)`。
- `ANY` 不改变分组，只为每个分组或无分组摘要返回一个代表值。
- 字段复用现有 `AGGREGATE_TERMS` 能力、Elements 相对路径和 Schema 验证链路。
- MongoDB 与 Elasticsearch 使用各自的原生聚合能力，不增加脚本、排序阶段或依赖。
- `null` 和缺失值不参与选择；全部为空时返回 `null`。
- OpenAPI、查询文档和双后端真实集成测试同步更新。

## 非目标

- 不保证同一查询的多次执行、不同分片布局或不同后端返回同一个代表值。
- 不提供 `FIRST`、`LAST`、`LATEST`、`ARG_MAX`、自定义排序或确定性选择策略。
- 不支持表达式、对象或多值字段作为 `ANY` 输入。
- 不增加通用选择表达式、字段 Catalog、Scanner、Lookup、Join 或结果补全服务。
- 不增加配置项、依赖、Gradle 模块、CI 工作流或发布逻辑。
- 不提供兼容别名、旧解析器适配、平行 `selections` 通道或其他源码、二进制、线协议兼容桥。

## 公共语义

给定以下逻辑数据：

| productId | productName |
| --- | --- |
| `p1` | `Apple` |
| `p1` | `Apple 2026` |
| `p1` | `null` |

查询：

```kotlin
aggregation {
    terms("productId", "productId")
    any("productName", "productName")
    count("count")
}
```

只返回一个 `p1` 分组。`productName` 可以是 `Apple` 或 `Apple 2026`，但不能因为名称不同拆分分组，也不能在存在非空名称时返回 `null`。

完整合同如下：

- `TERMS` 决定分组键；`ANY` 是指标，不参与分组。
- 每个 `ANY` 从当前聚合作用域选择一个非 `null`、非缺失的标量。
- 组内至少有一个有效值时返回其中任意一个；全部无值时返回 `null`。
- 没有 `groupBy` 时，从整个根快照或最内层 Element 作用域选择一个值，返回单行摘要。
- 字段值类型和结果规范化沿用现有 `TERMS` 分组键语义。
- 同一组存在多个不同值时，调用方不得依赖具体返回哪一个。
- MongoDB 与 Elasticsearch 可以为同一不一致数据选择不同值。

## 公共模型与 JSON

`AggregationMetric` 保持现有 sealed 层次并增加一个 subtype：

```kotlin
@JsonSubTypes(
    JsonSubTypes.Type(AggregationMetric.Count::class, name = "COUNT"),
    JsonSubTypes.Type(AggregationMetric.Numeric::class, name = "NUMERIC"),
    JsonSubTypes.Type(AggregationMetric.Any::class, name = "ANY"),
)
sealed interface AggregationMetric {
    val alias: String

    data class Any(
        val field: LogicalField,
        override val alias: String,
    ) : AggregationMetric
}
```

JSON 请求示例：

```json
{
  "groupBy": [
    {"type": "TERMS", "field": "productId", "alias": "productId"}
  ],
  "metrics": [
    {"type": "ANY", "field": "productName", "alias": "productName"},
    {"type": "COUNT", "alias": "count"}
  ]
}
```

`Any` 构造时复用现有聚合 alias 校验。`AggregationQuery` 现有的 group 与 metric alias 唯一性、sort 字段引用和数量上限自动覆盖 `ANY`。

新增 subtype 会影响下游对公开 sealed `AggregationMetric` 的穷尽式 `when`。本次需求已明确不考虑兼容，不改为非 sealed 接口，不增加 `else` 协议或第二套模型。

## Kotlin DSL

`AggregationQueryDsl` 只增加一个入口：

```kotlin
fun any(field: String, alias: String) {
    metrics += AggregationMetric.Any(LogicalField(field), alias)
}
```

不增加 `AnyDsl`、alias 推断、表达式重载或排序参数。需要在 DSL 外构造查询的调用方直接使用公开模型。

## 字段解析与 Schema

`QuerySchemaResolver.resolve(AggregationQuery)` 对每个 `AggregationMetric.Any`：

1. 按当前最内层 Element 的逻辑与物理父路径解析字段。
2. 要求字段具有 `QueryCapability.AGGREGATE_TERMS` binding。
3. 已知 `QueryCardinality.MANY` 时把查询标记为不兼容。
4. 继续由现有 `QuerySchemaValidationMode` 决定不兼容或 Schema 不可用时是拒绝、告警还是回退。

不新增 `AGGREGATE_ANY` capability。`ANY` 与 `TERMS` 需要相同的精确标量物理值，复用现有 capability 可以直接获得 Elasticsearch `keyword` 子字段、MongoDB 原生字段和动态子字段解析。

Schema 不可用且当前验证模式允许回退时，编译器沿用现有逻辑字段路径。调用方仍须满足公开的单值标量合同；本设计不收紧现有默认验证模式。

## MongoDB 编译

`MongoAggregationCompiler.group()` 把 `AggregationMetric.Any` 编译为：

```javascript
{ $max: "$<resolved-field>" }
```

选择 `$max` 是后端实现细节：最大非空值属于合法的 `ANY` 结果，同时避免 `$push` 全量物化、额外排序和自定义函数。

MongoDB `$max` 在 `$group` 中忽略部分 `null` 和缺失值；全部为空时返回 `null`。字段通过最内层 Element 作用域和 `AGGREGATE_TERMS` binding 解析。公开合同限制为单值字段，因此不为数组增加 `$unwind`、`$reduce` 或兼容处理。

投影阶段直接包含 `ANY` alias。`MongoSnapshotQueryService.toAggregationResult()` 对该值采用与 `TERMS` 分组键相同的结果规范化；不会把它转换为有限 `Double` 数值指标。

无分组且没有匹配文档时，现有 empty-summary 补全逻辑为 `ANY` 返回 `null`，`COUNT` 仍返回 `0L`，数值指标仍返回 `null`。

## Elasticsearch 编译与分页

`ElasticsearchAggregationCompiler` 为 `AggregationMetric.Any` 解析 `AGGREGATE_TERMS` 物理字段，并在当前聚合桶下生成一个 `terms` 子聚合：

```json
{
  "productName": {
    "terms": {
      "field": "state.productName.keyword",
      "size": 1
    }
  }
}
```

Elasticsearch 默认忽略缺失字段；`size = 1` 返回一个候选 term bucket。其 bucket key 即 `ANY` 结果，没有 bucket 时返回 `null`。默认按文档数选择最常见值只是实现细节，不提升为公共语义。

`ElasticsearchAggregationPlan` 明确区分 Count、Numeric 和 Any 三种内部指标计划；不继续用 `function == null` 隐式区分更多类型。`ElasticsearchAggregationPager` 在 grouped row 和 summary row 两条路径中读取 `ANY` 的唯一 bucket key，并规范化为与现有 group key 相同的原生标量结果。

`ANY` 不生成 runtime field、不使用 Painless、不读取 `_source`，也不使用 `top_hits`。PIT、Composite 分页、Elements nested/filter 包装和数值 value-count 逻辑不变。

## 排序与昂贵查询守卫

`ANY` alias 与其他 metric alias 一样可被 `sort` 引用：

- MongoDB 在投影后使用现有 `$sort`。
- Elasticsearch 复用现有 metric-sorted 分页和有界 Top-N 行选择。
- `HttpQueryGuardFilter` 现有“按指标 alias 排序”判断自然包含 `ANY`，`allow-expensive-operators=false` 时继续拒绝。

只读取 `ANY` 而不按其排序时不增加新的 HTTP expensive 条件。它使用后端原生字段聚合，与现有 `TERMS` 分组处于同一能力边界。

## 错误策略

- 非法 alias 和重复 alias 继续由 `AggregationQuery` 构造校验拒绝。
- 字段缺少 `AGGREGATE_TERMS`、已知为 `MANY` 或物理 mapping 不兼容时，由现有 Schema resolution 与 validation mode 处理。
- 未知 JSON subtype、缺少 `field`/`alias` 或额外属性由现有严格反序列化拒绝。
- 组内部分 `null` 或缺失不是错误；全部无值返回 `null`。
- 后端 mapping 漂移、Elasticsearch 聚合失败、MongoDB pipeline 失败或框架结果解析缺陷直接传播，不伪装成空值。
- 不统一 MongoDB 与 Elasticsearch 的错误文本，也不为 `ANY` 增加降级查询。

## OpenAPI 与文档

OpenAPI 的 `AggregationMetric` union 增加 `AggregationMetric.Any`，使用 `type: ANY` discriminator。新 Schema 包含必填 `field` 和 `alias`，不改变聚合端点、RequestBody 名称或动态响应类型。

同步更新：

- `wow-openapi` 示例域 OpenAPI 与 contract 快照。
- `documentation/docs/zh/guide/query.md`。
- `documentation/docs/en/guide/query.md`。

文档示例展示 `terms(productId) + any(productName) + count`，明确名称不一致时结果不稳定，以及 `ANY` 不等同于增加第二个 `TERMS` 分组。

不直接修改 compensation dashboard 生成客户端；生成客户端更新不属于本设计范围。

## 测试策略

### 公共模型、DSL 与 Schema

- `wow-api` 验证 `ANY` JSON 反序列化、序列化、必填字段和 alias 规则。
- 验证 `ANY` 与 group、Count、Numeric alias 冲突仍被拒绝。
- `wow-query` 验证 `any(field, alias)` 生成准确模型。
- `QuerySchemaResolver` 验证 `AGGREGATE_TERMS`、最内层 Element 相对路径、SINGLE 接受和 MANY 不兼容。

### MongoDB

- 编译器测试验证 `$max` 使用解析后的物理字段，且投影包含 alias。
- 真实集成测试写入同一 `productId` 下两个不同名称、一个 `null`，断言只返回一个分组、名称属于非空候选集合、Count 保持完整计数。
- 覆盖全部名称为空、无分组摘要和 Elements 相对字段。

### Elasticsearch

- 编译器测试验证 `terms(field, size = 1)`，且不生成 runtime mapping 或 script。
- Pager 测试覆盖 string、integer、double、boolean bucket key 与空 bucket。
- 真实集成测试执行与 MongoDB 相同的代表值、全空、摘要和 Elements 场景。
- 覆盖按 `ANY` alias 排序继续走现有 metric-sorted 路径。

### 合同与守卫

- `wow-webflux` 验证按 `ANY` alias 排序受 `allow-expensive-operators` 控制，不排序时不新增限制。
- `wow-openapi` 验证 union/discriminator 和必填字段，更新 JSON 快照。
- 文档构建验证中英文示例和链接。

## 影响范围

预计只修改现有职责内文件：

- `wow-api/.../AggregationQuery.kt` 及其测试。
- `wow-query/.../AggregationQueryDsl.kt`、`QuerySchemaResolver.kt` 及其测试。
- MongoDB aggregation compiler、snapshot query service 及测试。
- Elasticsearch aggregation compiler、plan/pager 及测试。
- `HttpQueryGuardFilter` 测试；生产代码仅在现有通用判断不能自然覆盖时修改。
- OpenAPI 测试与快照。
- 中英文 query 文档。

不增加 endpoint、service、module、dependency、配置或兼容层。

## 验证

实现阶段从最窄测试开始，完成前至少运行：

```bash
./gradlew \
  :wow-api:check \
  :wow-query:check \
  :wow-mongo:check \
  :wow-elasticsearch:check \
  :wow-webflux:check \
  :wow-openapi:check

./gradlew \
  :wow-mongo:integrationTest \
  :wow-elasticsearch:integrationTest \
  --stacktrace

cd documentation && pnpm docs:build
```

若改动触及 Detekt 所覆盖的生产文件，再运行根 `./gradlew detekt`。不以编译器 BSON/JSON 字符串断言代替双后端真实执行证据。

## 完成条件

- `AggregationMetric.Any`、JSON `ANY` 和 DSL `any(field, alias)` 合同一致。
- `terms(productId) + any(productName)` 对名称不一致的数据只产生一个 ID 分组。
- 部分空值仍返回非空代表值，全部空值返回 `null`。
- 已知 MANY 字段按现有验证模式判定为不兼容。
- MongoDB `$max` 与 Elasticsearch `terms(size = 1)` 均通过真实集成测试。
- Count、Numeric、Elements、Composite 分页、排序和空摘要现有行为没有回归。
- OpenAPI union/快照与中英文文档同步。
- 没有新增脚本、兼容层、依赖、配置、模块或 CI 改动。

## 参考资料

- [MongoDB `$max` accumulator](https://www.mongodb.com/docs/manual/reference/operator/aggregation/max/)
- [Elasticsearch terms aggregation](https://www.elastic.co/docs/reference/aggregations/search-aggregations-bucket-terms-aggregation)
