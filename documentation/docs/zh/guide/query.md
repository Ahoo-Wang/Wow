---
title: 查询服务
description: 使用 FilterExpression、查询 DSL 与 REST API 查询快照和事件流。
---

# 查询服务

`wow-mongo` 与 `wow-elasticsearch` 提供查询服务实现。查询 API 使用单层 `FilterExpression` 描述过滤语义；存储模块负责将其编译为后端查询。

## FilterExpression

`FilterExpression` 是密封接口。每个表达式只使用 `op` 作为 JSON 类型判别字段，不再同时声明 `type` 与 `operator`。

```json
{
  "op": "AND",
  "operands": [
    { "op": "EQ", "field": "state.status", "value": "CREATED" },
    { "op": "DELETION", "state": "ACTIVE" }
  ]
}
```

### 操作符

| 分类 | `op` | 主要字段 | 说明 |
|---|---|---|---|
| 常量 | `MATCH_ALL`、`MATCH_NONE` | - | 匹配全部或不匹配任何记录 |
| 元数据 | `ID`、`IDS`、`AGGREGATE_ID`、`AGGREGATE_IDS`、`TENANT_ID`、`OWNER_ID`、`SPACE_ID` | `value` 或 `values` | 查询文档 ID、聚合 ID 或消息元数据；只能作为查询根表达式 |
| 逻辑 | `AND`、`OR`、`NOR` | `operands` | `operands` 至少包含一个表达式 |
| 比较 | `EQ`、`NE`、`GT`、`GTE`、`LT`、`LTE` | `field`、`value` | `EQ`、`NE` 允许 `null`，并规范化为判空表达式 |
| 字符串 | `CONTAINS`、`STARTS_WITH`、`ENDS_WITH` | `field`、`value`、`stringComparison` | `stringComparison` 默认为 `CASE_SENSITIVE` |
| 集合 | `IN`、`NOT_IN`、`CONTAINS_ALL` | `field`、`values` | `values` 非空且元素不能为 `null` |
| 范围 | `BETWEEN` | `field`、`lowerBound`、`upperBound` | 两个边界都包含在范围内 |
| 空值与存在性 | `IS_EMPTY`、`IS_NULL`、`IS_NOT_NULL`、`EXISTS`、`NOT_EXISTS` | `field` | 按各后端原生的存在性与空值语义编译 |
| 删除状态 | `DELETION` | `state` | `ACTIVE`、`DELETED` 或 `ALL`；删除状态本身也是过滤器 |
| 数组元素 | `ELEMENT_MATCH` | `field`、`predicate` | `predicate` 内不允许 `DELETION`、`SEARCH` 或元数据 Filter |
| 全文搜索 | `SEARCH` | `query`、`fields`、`mode` | `mode` 默认为 `TERMS`，可设为 `PHRASE`；具体字段能力由后端决定 |
| 相对时间 | `TODAY`、`YESTERDAY`、`BEFORE_TODAY`、`TOMORROW`、`THIS_WEEK`、`NEXT_WEEK`、`LAST_WEEK`、`THIS_MONTH`、`NEXT_MONTH`、`LAST_MONTH`、`LAST_YEAR`、`THIS_YEAR`、`NEXT_YEAR`、`RECENT_DAYS`、`EARLIER_DAYS` | `field`；特定操作使用 `time` 或 `days`；可选 `zoneId`、`datePattern`、`timeUnit` | 执行前统一规范化为绝对时间范围 |

相对时间过滤器面向数值时间字段时，可将 `timeUnit` 配置为 `java.util.concurrent.TimeUnit` 的枚举名，默认值为 `MILLISECONDS`。配置 `datePattern` 时输出字符串，`timeUnit` 不参与格式化。

`field` 是逻辑字段路径。合法示例：

```text
state.status
state.items.0.productId
```

字段段以字母或下划线开头，可包含字母、数字、下划线和连字符；数组索引允许使用纯数字段。物理字段映射由 MongoDB 或 Elasticsearch 查询实现负责。

聚合级 OpenAPI 请求体通过 `x-wow-query-fields` 发布可用的过滤、投影和排序字段路径。即使 `/state` 响应已解包 `state` 对象，查询仍应使用这里声明的路径；例如响应中的 `status` 应查询为 `state.status`。

快照查询默认使用 `DELETION = ACTIVE`。顶层 `DELETION`，或顶层 `AND` 的直接 `DELETION` 子项，可以显式覆盖该范围；嵌套在 `OR` 或 `NOR` 中的删除过滤器不会关闭 active guard。事件流查询不会自动追加删除状态过滤，以保证审计事件完整。

:::info 后端差异
MongoDB 的 `SEARCH` 使用集合文本索引，不会把查询限制到 `fields`；Elasticsearch 可解析搜索字段和多字段映射。`PHRASE` 在 MongoDB 中编译为带引号的 `$text` 短语，在 Elasticsearch 中编译为 `multi_match(type = phrase)`。`ELEMENT_MATCH` 的子字段建议使用相对路径，以同时兼容 MongoDB 与 Elasticsearch。
:::

## Kotlin DSL

使用 `filterExpression` 构造独立过滤器：

```kotlin
val orderFilter = filterExpression {
    deletion(DeletionState.ACTIVE)
    "state.status" eq "CREATED"
    "state.totalAmount" gte 100
    "state.customerName".contains(
        "wang",
        StringComparison.CASE_INSENSITIVE,
    )
    "state.tags" containsAll listOf("priority", "online")
    "state.items".elementMatch {
        "productId" eq "product-1"
        "quantity" gt 0
    }
}
```

使用 `PHRASE` 搜索后端分析器识别的连续词项；省略 `mode` 时保持原有 `TERMS` 行为：

```kotlin
val phraseFilter = filterExpression {
    search("event sourcing", SearchMode.PHRASE, "state.title", "state.description")
}
```

使用专用函数查询聚合与消息元数据：

```kotlin
val filter = filterExpression {
    aggregateId("order-1")
    tenantId("tenant-1")
}
```

元数据 Filter 是查询根表达式，不能嵌套到 `elementMatch` 中。

同一 DSL 块内的多个表达式自动组合为 `AND`。需要显式逻辑关系时使用 `and`、`or` 或 `nor`：

```kotlin
val filter = filterExpression {
    or {
        "state.status" eq "CREATED"
        "state.status" eq "PAID"
    }
    nor {
        "state.channel" eq "TEST"
    }
}
```

使用 `String.path` 为块内的相对字段设置词法路径作用域，`pathState` 等价于 `"state".path`。嵌套 `path` 会追加相对路径。只有以当前作用域加 `.` 开头的路径才视为已经限定，因此与作用域同名的字段仍按相对字段处理。退出代码块后自动回到父级路径。`path` 块内的多个表达式组成一个隐式 `AND` 操作数，即使它位于 `or` 或 `nor` 中也不会被摊平：

```kotlin
val filter = filterExpression {
    pathState {
        "status" eq "CREATED"
        "customer".path {
            "id" eq customerId
        }
    }
    "tenantId" eq tenantId
}
```

`expression(...)` 只能在当前查询上下文根直接加入已经构造的表达式，不能在 `path` 作用域内调用，包括经由已弃用的 `nested` 块调用。`deletion(...)` 同样属于查询根作用域，在 `path` 内会被拒绝。预构造表达式中的 `LogicalField` 必须已经适配插入位置的查询上下文；例如，`elementMatch` 的独立元素根上下文使用元素相对路径。

### 查询 DSL

`singleQuery`、`listQuery` 和 `pagedQuery` 统一使用 `filter {}`：

```kotlin
val query = pagedQuery {
    filter {
        "state.status" eq "CREATED"
        "state.createTime".recentDays(7, ZoneId.of("Asia/Shanghai"))
        "state.createTime".yesterday(ZoneId.of("Asia/Shanghai"))
        "state.createTime".nextMonth(ZoneId.of("Asia/Shanghai"))
        "state.createTime".thisYear(ZoneId.of("Asia/Shanghai"))
    }
    projection {
        include("aggregateId")
        include("state.status")
    }
    sort {
        "state.createTime".desc()
    }
    pagination {
        index(1)
        size(20)
    }
}

query.query(queryService)
```

`ListQuery.limit = 0` 表示不限制结果数量；HTTP 查询仍会受到 WebFlux 查询成本保护配置约束。

### 快照聚合

使用 `aggregation {}` 按顺序展开状态中的集合链，并返回表格型结果：

```kotlin
val query = aggregation {
    expand("state.orders") { "status" eq "PAID" }
    expand("lines") { "quantity" gt 0 }
    terms("productId", "product")
    sum("amount", "total")
    sort { "total".desc() }
    limit(20)
}

query.query(snapshotQueryService)
```

等价 JSON 为：

```json
{
  "elements": [
    {
      "path": "state.orders",
      "filter": { "op": "EQ", "field": "status", "value": "PAID" }
    },
    {
      "path": "lines",
      "filter": { "op": "GT", "field": "quantity", "value": 0 }
    }
  ],
  "groupBy": [
    { "type": "TERMS", "field": "productId", "alias": "product" }
  ],
  "metrics": [
    {
      "type": "NUMERIC",
      "function": "SUM",
      "expression": { "field": "amount" },
      "alias": "total"
    }
  ],
  "sort": [{ "field": "total", "direction": "DESC" }],
  "limit": 20
}
```

第一个 Element 路径是快照绝对路径；后续每个 Element 路径及每个 Element filter 都相对当前已展开元素。group 与 metric 字段相对最内层 Element；没有 Elements 时，它们使用快照绝对路径。Elements 只表示一条父子链，不支持兄弟集合展开。

分组支持 `TERMS`、`HISTOGRAM` 与 `DATE_HISTOGRAM`。指标支持 `COUNT`、`SUM`、`AVG`、`MIN` 与 `MAX`；`COUNT` 返回 `Long`，数值指标返回有限 `Double`，没有值参与计算时返回 `null`。没有分组的查询返回一行汇总，空数据集同样如此（`COUNT = 0`，数值指标为 `null`）。分组结果最多返回 `limit` 行；默认值为 `100`，最大值为 `10,000`。

排序字段引用 group 或 metric alias。未显式排序的 group alias 会按声明顺序追加，以保证结果稳定。按 metric alias 排序成本较高，受 WebFlux `query.allow-expensive-operators` 护栏控制。固定结构上限为 5 个 Elements、32 个 groups、64 个 metrics 与 32 个有效排序字段。

聚合复用现有快照过滤链：ABAC 与路由 filter 仍会追加到根 filter。Masking filter 会忽略聚合查询，因此已配置的 masker 不会拒绝或重写聚合结果。

Wow 只校验请求结构，不校验字段是否存在、路径是否为集合或物理字段类型；不会维护聚合字段目录，也不会使用 `TypeFieldPaths` 做校验。自定义 Jackson serializer、后端 filter converter 或 Elasticsearch mapping 不保证跨后端等价。首期不包含 Batch 聚合与算术表达式。

HTTP 端点为 `POST /{aggregate}/snapshot/aggregation`。tenant、owner 或 space 作用域的聚合会在前面增加各自的路由前缀；以运行实例的 OpenAPI 路径为准。JSON 响应是动态对象数组；SSE 逐个流式返回对象。OpenAPI 为每个聚合发布专属 `AggregationQuery` request body，其 `x-wow-query-fields` 引用该聚合的 `*AggregatedFields` 组件，JSON schema 仍使用通用 `AggregationQuery` 合同。

#### 场景案例

以下请求体都发送到对应聚合的 `snapshot/aggregation` 端点。为突出聚合结构，除第一个案例外省略重复的 `curl` 外壳。

##### 按分类统计数量

补偿控制面可以按执行状态统计记录数量：

```bash
curl --request POST 'http://localhost:8080/execution_failed/snapshot/aggregation' \
  --header 'Content-Type: application/json' \
  --data '{
    "groupBy": [
      {"type": "TERMS", "field": "state.status", "alias": "status"}
    ],
    "metrics": [
      {"type": "COUNT", "alias": "count"}
    ],
    "sort": [
      {"field": "status", "direction": "ASC"}
    ],
    "limit": 10
  }'
```

响应结构如下，具体计数取决于当前数据：

```json
[
  {"status": "FAILED", "count": 12},
  {"status": "SUCCEEDED", "count": 3}
]
```

##### 过滤后汇总整体指标

不声明 `groupBy` 时，查询只返回一行。下面的查询先筛选失败记录，再统计数量、平均重试次数和最大重试次数：

```json
{
  "filter": {"op": "EQ", "field": "state.status", "value": "FAILED"},
  "metrics": [
    {"type": "COUNT", "alias": "failedCount"},
    {
      "type": "NUMERIC",
      "function": "AVG",
      "expression": {"field": "state.retryState.retries"},
      "alias": "averageRetries"
    },
    {
      "type": "NUMERIC",
      "function": "MAX",
      "expression": {"field": "state.retryState.retries"},
      "alias": "maxRetries"
    }
  ]
}
```

```json
[
  {"failedCount": 12, "averageRetries": 1.5, "maxRetries": 4.0}
]
```

即使过滤后没有数据，仍返回一行：`failedCount` 为 `0`，两个数值指标为 `null`。

##### 按数值区间观察分布

订单可以按总金额分桶；`interval: 100` 表示 `[0, 100)`、`[100, 200)` 等区间，响应中的 `amountRange` 是区间下界：

```json
{
  "groupBy": [
    {
      "type": "HISTOGRAM",
      "field": "state.totalAmount",
      "alias": "amountRange",
      "interval": 100
    }
  ],
  "metrics": [
    {"type": "COUNT", "alias": "orderCount"},
    {
      "type": "NUMERIC",
      "function": "SUM",
      "expression": {"field": "state.totalAmount"},
      "alias": "totalAmount"
    }
  ],
  "sort": [{"field": "amountRange", "direction": "ASC"}],
  "limit": 20
}
```

```json
[
  {"amountRange": 0.0, "orderCount": 8, "totalAmount": 356.0},
  {"amountRange": 100.0, "orderCount": 5, "totalAmount": 642.0}
]
```

##### 按业务时间查看趋势

假设业务状态中的 `state.createdAt` 是可执行日期字段，可以按上海时区统计每日新增数量：

```json
{
  "groupBy": [
    {
      "type": "DATE_HISTOGRAM",
      "field": "state.createdAt",
      "alias": "day",
      "unit": "DAY",
      "timeZone": "Asia/Shanghai"
    }
  ],
  "metrics": [{"type": "COUNT", "alias": "createdCount"}],
  "sort": [{"field": "day", "direction": "ASC"}],
  "limit": 31
}
```

```json
[
  {"day": 1787500800000, "createdCount": 18},
  {"day": 1787587200000, "createdCount": 23}
]
```

日期桶键是分桶起点的 epoch 毫秒。MongoDB 字段必须能转换为日期；Elasticsearch 字段必须映射为 `date` 或 `date_nanos`。

##### 展开集合并取 Top-N

订单商品是集合。先用绝对路径展开 `state.items`，再使用相对路径过滤商品、分组并汇总销量：

```json
{
  "filter": {"op": "EQ", "field": "state.status", "value": "PAID"},
  "elements": [
    {
      "path": "state.items",
      "filter": {"op": "GT", "field": "quantity", "value": 0}
    }
  ],
  "groupBy": [
    {"type": "TERMS", "field": "productId", "alias": "productId"}
  ],
  "metrics": [
    {
      "type": "NUMERIC",
      "function": "SUM",
      "expression": {"field": "quantity"},
      "alias": "totalQuantity"
    }
  ],
  "sort": [{"field": "totalQuantity", "direction": "DESC"}],
  "limit": 10
}
```

```json
[
  {"productId": "product-1", "totalQuantity": 42.0},
  {"productId": "product-2", "totalQuantity": 31.0}
]
```

根 `filter` 仍使用快照绝对路径；Element filter、group 和 metric 字段都相对当前展开的商品。按 `totalQuantity` 这类 metric alias 排序属于高成本操作。`query.allow-expensive-operators=false` 时，本例会因 Elements 展开和 metric alias 排序被 HTTP 护栏拒绝；需要启用该配置，或移除这两项能力。

同一份 `AggregationQuery` 合同可由 MongoDB 与 Elasticsearch 快照查询服务执行并返回相同的行结构；字段映射、嵌套模型及自定义序列化的后端差异仍遵循各扩展文档中的约束。

### 重写查询

查询过滤器通过 `withFilter` 或 `appendFilter` 重写，不再操作内部 `Condition`：

```kotlin
val warehouseFilter = filterExpression {
    "state.warehouseId" eq warehouseId
}
context.appendFilter(warehouseFilter)
```

## REST API

### 分页查询

```http
POST /tenant/tenant-1/sales-order/snapshot/paged
Content-Type: application/json
Wow-Space-Id: space-1
```

```json
{
  "filter": {
    "op": "AND",
    "operands": [
      { "op": "EQ", "field": "state.status", "value": "CREATED" },
      { "op": "DELETION", "state": "ACTIVE" }
    ]
  },
  "projection": {
    "include": ["aggregateId", "state.status"]
  },
  "sort": [
    { "field": "state.createTime", "direction": "DESC" }
  ],
  "pagination": {
    "index": 1,
    "size": 20
  }
}
```

### 列表与单条查询

列表和单条查询同样使用 `filter`。列表请求使用 `limit`，单条请求不需要分页字段：

```json
{
  "filter": { "op": "AGGREGATE_ID", "value": "order-1" },
  "limit": 1,
  "sort": []
}
```

### 计数

在 JVM 中直接调用 typed 扩展：`filter.count(queryService)`。`Condition.count(...)` 扩展仍保留，但已标记弃用。

计数请求体就是一个 `FilterExpression`，外层没有 `filter`：

```http
POST /tenant/tenant-1/sales-order/snapshot/count
Content-Type: application/json
```

```json
{
  "op": "EQ",
  "field": "state.status",
  "value": "CREATED"
}
```

新格式执行严格反序列化：未知字段、缺少必填字段、空逻辑操作数、非法逻辑字段或不符合类型约束的值都会返回请求错误。

## 兼容与迁移

旧 `Condition` DTO、`Operator` 和 `ConditionDsl` 仍保留但已标记弃用。旧查询构造器、`QueryService.count(Condition)` 和 `Condition.count(...)` 会立即把 `Condition` 转换为 `FilterExpression`；查询对象与执行链此后只保留 `filter`。

REST 迁移期间：

- `single`、`list`、`paged` 请求必须且只能提供 `filter` 或 `condition` 之一；
- `count` 请求必须且只能提供新格式的 `op` 或旧格式的 `operator` 之一；
- OpenAPI 只发布新的 `FilterExpression` 格式；
- 旧 `condition` 请求仅在可转换为合法 `FilterExpression` 时接受；`MATCH` 现在遵循 `SEARCH` 语义，不能出现在 `ELEM_MATCH` 内，也不再使用原 Elasticsearch 精确字段映射；
- `RAW` 已删除且没有替代操作符。需要后端原生查询时，应由应用自有端点和安全策略负责。

旧格式示例：

```json
{
  "condition": {
    "field": "state.status",
    "operator": "EQ",
    "value": "CREATED"
  },
  "limit": 20
}
```

## JSON Schema

规范文件：

- [`filter-expression.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/filter-expression.schema.json)
- [`single-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/single-query.schema.json)
- [`list-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/list-query.schema.json)
- [`paged-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/paged-query.schema.json)
- [`count-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/count-query.schema.json)

## 查询服务注册器

`SnapshotQueryServiceRegistrar` 会把本地聚合根查询服务注册到 Spring 容器。Bean 名称为 `聚合根名称 + ".SnapshotQueryService"`。

```kotlin
class OrderService(
    private val queryService: SnapshotQueryService<OrderState>,
) {
    fun getById(id: String): Mono<OrderState> = singleQuery {
        filter {
            aggregateId(id)
        }
    }.query(queryService).toState().throwNotFoundIfEmpty()
}
```
