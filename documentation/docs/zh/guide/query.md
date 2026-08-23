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
| 逻辑 | `AND`、`OR`、`NOR` | `operands` | `operands` 至少包含一个表达式 |
| 比较 | `EQ`、`NE`、`GT`、`GTE`、`LT`、`LTE` | `field`、`value` | `EQ`、`NE` 允许 `null`，并规范化为判空表达式 |
| 字符串 | `CONTAINS`、`STARTS_WITH`、`ENDS_WITH` | `field`、`value`、`stringComparison` | `stringComparison` 默认为 `CASE_SENSITIVE` |
| 集合 | `IN`、`NOT_IN`、`CONTAINS_ALL` | `field`、`values` | `values` 非空且元素不能为 `null` |
| 范围 | `BETWEEN` | `field`、`lowerBound`、`upperBound` | 两个边界都包含在范围内 |
| 空值与存在性 | `IS_EMPTY`、`IS_NULL`、`IS_NOT_NULL`、`EXISTS`、`NOT_EXISTS` | `field` | 按各后端原生的存在性与空值语义编译 |
| 删除状态 | `DELETION` | `state` | `ACTIVE`、`DELETED` 或 `ALL`；删除状态本身也是过滤器 |
| 数组元素 | `ELEMENT_MATCH` | `field`、`predicate` | `predicate` 内不允许 `DELETION` 或 `SEARCH` |
| 全文搜索 | `SEARCH` | `query`、`fields` | `query` 不能为空；具体字段能力由后端决定 |
| 相对时间 | `TODAY`、`BEFORE_TODAY`、`TOMORROW`、`THIS_WEEK`、`NEXT_WEEK`、`LAST_WEEK`、`THIS_MONTH`、`LAST_MONTH`、`RECENT_DAYS`、`EARLIER_DAYS` | `field`；特定操作使用 `time` 或 `days`；可选 `zoneId` | 执行前统一规范化为绝对时间范围 |

`field` 是逻辑字段路径。合法示例：

```text
state.status
state.items.0.productId
```

字段段以字母或下划线开头，可包含字母、数字、下划线和连字符；数组索引允许使用纯数字段。物理字段映射由 MongoDB 或 Elasticsearch 查询实现负责。

聚合级 OpenAPI 请求体通过 `x-wow-query-fields` 发布可用的过滤、投影和排序字段路径。即使 `/state` 响应已解包 `state` 对象，查询仍应使用这里声明的路径；例如响应中的 `status` 应查询为 `state.status`。

快照查询默认使用 `DELETION = ACTIVE`。顶层 `DELETION`，或顶层 `AND` 的直接 `DELETION` 子项，可以显式覆盖该范围；嵌套在 `OR` 或 `NOR` 中的删除过滤器不会关闭 active guard。事件流查询不会自动追加删除状态过滤，以保证审计事件完整。

:::info 后端差异
MongoDB 的 `SEARCH` 使用集合文本索引，不会把查询限制到 `fields`；Elasticsearch 可解析搜索字段和多字段映射。`ELEMENT_MATCH` 的子字段建议使用相对路径，以同时兼容 MongoDB 与 Elasticsearch。
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

### 重写查询

查询过滤器通过 `withFilter` 或 `appendFilter` 重写，不再操作内部 `Condition`：

```kotlin
context.asRewritableQuery().rewriteQuery { query ->
    val warehouseFilter = filterExpression {
        "state.warehouseId" eq warehouseId
    }
    query.appendFilter(warehouseFilter)
}
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
  "filter": { "op": "EQ", "field": "aggregateId", "value": "order-1" },
  "limit": 1,
  "sort": []
}
```

### 计数

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

旧 `Condition`、`Operator`、`ConditionDsl`、`ConditionCapable` 和 `RewritableCondition` 保留但已标记弃用。旧查询构造器以及 `QueryService.count(Condition)` 仍会把 `Condition` 转换为 `FilterExpression`。

REST 迁移期间：

- `single`、`list`、`paged` 请求必须且只能提供 `filter` 或 `condition` 之一；
- `count` 请求必须且只能提供新格式的 `op` 或旧格式的 `operator` 之一；
- OpenAPI 只发布新的 `FilterExpression` 格式；
- 旧 `condition` 请求仍可读取，但新客户端应立即改用 `filter`；
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

## 快照 Elements 聚合

快照聚合只暴露 MongoDB 与 Elasticsearch 都能精确执行的表格语义。HTTP 入口为
`POST {aggregate-path}/snapshot/aggregation`，支持 `application/json` 与
`text/event-stream`。结果中的分组键和指标均使用显式 alias。

### Kotlin DSL

```kotlin
aggregationQuery {
    filter { "state.status" eq "CREATED" }
    expand("state.items") {
        filter { "quantity" gt 0 }
        groupBy("productId", "productId")
        sum("totalPrice", "totalAmount")
        count("lineCount")
        sort { "totalAmount".desc() }
        limit(100)
    }
}
```

`expand` 块内的字段使用相对路径；构建出的 `AggregationQuery` 会统一转换为绝对路径。
`groupBy`、指标、排序和 limit 只能声明在最内层；每层最多一个子 `expand`。

### HTTP JSON

直接构造 Kotlin 模型或发送 JSON 时，Elements、过滤器、分组和指标中的字段都必须使用绝对路径。
`type` 是 Group、Metric 和 Expression 的 Jackson discriminator。
这个新聚合端点只接受 `filter`，旧 `condition` 请求会被拒绝。
生成的 OpenAPI 会分别给出 Elements、Terms、Numeric 与 Temporal 字段枚举，避免客户端选择运行时必然拒绝的字段类型。
以下示例包含 Elements 和指标排序，因此被归类为高成本请求。默认配置允许该请求；如需拒绝此类请求，
请设置 `wow.webflux.query.allow-expensive-operators=false`。

::: code-group

```shell [请求]
curl -X POST \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/aggregation' \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
    "filter": {
      "op": "EQ",
      "field": "state.status",
      "value": "CREATED"
    },
    "elements": [
      {
        "path": "state.items",
        "filter": {
          "op": "GT",
          "field": "state.items.quantity",
          "value": 0
        }
      }
    ],
    "groupBy": [
      {
        "type": "TERMS",
        "field": "state.items.productId",
        "alias": "productId"
      }
    ],
    "metrics": [
      {
        "type": "NUMERIC",
        "function": "SUM",
        "expression": {
          "type": "FIELD",
          "field": "state.items.totalPrice"
        },
        "alias": "totalAmount"
      },
      {
        "type": "COUNT",
        "alias": "lineCount"
      }
    ],
    "sort": [
      {
        "field": "totalAmount",
        "direction": "DESC"
      }
    ],
    "limit": 100
  }'
```

```json [响应]
[
  {
    "productId": "product-1001",
    "totalAmount": 128.0,
    "lineCount": 4
  },
  {
    "productId": "product-1002",
    "totalAmount": 96.5,
    "lineCount": 2
  }
]
```

:::

将 `Accept` 改为 `text/event-stream` 时，每个结果行独立发送；查询语义与 JSON 响应完全相同。

### 来源与字段作用域

- `elements=[]` 表示在根 Snapshot 上聚合；否则按从外到内顺序声明严格父子对象集合链。
- Elements 只接受对象集合或对象数组；Map、标量集合、集合/数组嵌套集合、重复路径、跳过中间集合和兄弟集合笛卡尔积都会被拒绝。
- `groupBy`、指标及表达式字段必须属于最内层来源，不能隐式访问父级、兄弟或未展开的子集合。
- 每层 `AggregationElement.filter` 只能访问该层标量字段或非集合对象下的标量后代，不能直接过滤对象路径，也不能使用 `ELEMENT_MATCH`、`SEARCH` 或 `DELETION`。
- 精确匹配、集合匹配与范围操作符都会校验 literal 类型：数值字段使用 JSON number，时间/文本/UUID/枚举字段使用 JSON string，Boolean 字段使用 JSON boolean；null 判断必须使用 `IS_NULL`/`IS_NOT_NULL`。字符串操作符只接受文本字段，相对时间操作符只接受时间字段。
- Element 相对时间过滤器使用标准序列化的时间值并允许指定 `zoneId`；自定义 `datePattern` 无法在 MongoDB 与 Elasticsearch 之间保证可移植执行，因此会被拒绝。
- OpenAPI 会发布完整的合法 Elements 链，并按最内层来源与操作符类型收窄 Element filter、groupBy 和 metric 字段枚举；客户端不会把跳层、逆序、重复、父级、兄弟或错误类型字段提示为合法组合。
- 根 `ELEMENT_MATCH` 只筛选“包含匹配元素的快照”，不会筛选随后展开的行；行过滤必须写入对应 Element filter。
- 缺失、`null`、空集合及集合中的 `null` 成员都不产生展开行；任一分组字段缺失或为 `null` 时，该行不进入 bucket。

### 分组与指标

| 类型 | 输入约束 | 结果 |
|---|---|---|
| `TERMS` | 字符串、枚举、UUID、Boolean 或数值标量；拒绝 temporal | 整数 key 归一化为 `Long`，浮点/Decimal key 归一化为 `Double` |
| `HISTOGRAM` | 数值标量；`interval` 必须有限且大于 0；首版无 offset | bucket key 为 `Double` |
| `DATE_HISTOGRAM` | 支持的 temporal/`Date` 字段；unit 为 `YEAR`、`QUARTER`、`MONTH`、`WEEK`、`DAY`、`HOUR`、`MINUTE`、`SECOND` | epoch milliseconds `Long`；`WEEK` 从 Monday 开始 |
| `COUNT` | 无字段 | 根查询统计快照数；Elements 查询统计最内层展开行，返回 `Long` |
| `NUMERIC` | `SUM`、`AVG`、`MIN`、`MAX` + `FIELD` 数值表达式 | `Double?`；缺失值被忽略，空集 `SUM=0.0`，其余为 `null` |

`DateHistogram.timeZone` 默认为 `UTC`，只接受当前 Wow 版本内置的可移植 IANA ID（如 `Asia/Shanghai`）或
`±HH:MM`；`Z`、`UTC+08:00` 等非约定格式会被拒绝。任何非有限 Numeric metric 结果都会使整个查询失败。

### 结果、排序与空集

- 无 `groupBy` 时始终返回一行，禁止 sort，limit 仍需合法但不会改变单行结果。
- 有 `groupBy` 且没有 bucket 时返回空流。
- 默认按 groupBy 声明顺序升序；显式 sort 后会追加尚未出现的 group alias 升序，保证结果稳定。
- sort 字段必须唯一且只能引用输出 alias。升序时 `null` 在前，降序时 `null` 在后。
- 显式 sort 与自动追加的稳定 group alias 合计最多 32 个；例如 32 个 groupBy 后不能再按 metric 排序。
- 仅按 group alias 排序时，后端达到 limit 即可停止；按 metric alias 排序时必须完整遍历 bucket，再计算精确 Top-N。
- alias 在 groupBy 与 metrics 间全局唯一；不能为空、包含 `.`/NUL、以 `$` 或 `__wow_` 开头，也不能为 `_id`。

### 限制与 HTTP 防护

| 项目 | 公共模型 | HTTP 默认 |
|---|---:|---:|
| Elements 层数 | 5 | 3 |
| Element/group/expression 字段路径段数 | 10 | 10 |
| groupBy 数量 | 32 | 32 |
| metrics 数量 | 1..64 | 1..32 |
| 有效排序字段（含稳定决胜字段） | 32 | 32 |
| limit | 默认 100，最大 10,000 | 有分组时还受 `max-list-size=1000` 限制 |
| 根过滤器与全部 Element filters | — | 合计最多 `max-condition-nodes=64` 个节点 |

将 `max-aggregation-elements`、`max-aggregation-metrics` 或 `max-list-size` 设为 `0`
只会关闭对应 HTTP 上限，公共模型硬上限仍然生效。以下请求会被归类为高成本请求，并在
`allow-expensive-operators=false` 时被拒绝：

- 包含任意 Elements；
- 根过滤器在受信任的 tenant/owner/space 路由过滤后仍为 match-all；
- sort 引用任意 metric alias。

HTTP guard 只统计用户提交的根过滤器与 Element filters。受信任的 tenant/owner/space 路由过滤器不消耗用户预算，
并可在成本分类前约束 match-all 根过滤器；ABAC 随后执行，不改变本次分类。
`AbacQueryFilter.resolveAggregationFilter` 是聚合专用授权扩展点；默认按返回数据的 `DYNAMIC_LIST` 语义执行，绝不会伪装成 `COUNT`。
自定义 Snapshot `QueryFilter` 必须通过 `SnapshotAggregationQueryFilterProvider` 提供等价的聚合策略；否则聚合端点会 fail-closed，避免绕过既有授权或改写规则。
Snapshot 配置 masker 时，聚合会在访问后端前 fail-closed。
HTTP 层不维护重复的字段白名单；聚合元数据 Validator 统一校验集合链、字段归属和可移植类型。
同一 Validator 也在 NoOp、MongoDB 与 Elasticsearch Service 入口执行，直接调用 `SnapshotQueryService.aggregate` 或 DSL 不会绕过领域约束。
指标和分组没有脚本入口。

### 后端失败与性能边界

- Elasticsearch 要求每层 Elements 映射为 `nested`，`DateHistogram` 映射为 `date`/`date_nanos`；普通 `object` 或 epoch `long` 会被拒绝。
- MongoDB 使用逐层 `$unwind`；字符串分组和排序固定使用 `simple` collation。
- timeout、分片失败、响应结构缺失、类型转换失败或非有限指标结果都会使整个查询失败，不返回部分结果。

当前单线程工程基线使用 10,000 个快照、每快照 100 个叶子元素。Elements group-key 排序约为
MongoDB `393–1,639 ms/op`、Elasticsearch `1–8 ms/op`；精确 metric Top-N 约为
MongoDB `1.61–1.84 s/op`、Elasticsearch `1.79 s/op`。这些数值是一次 JMH 运行的点估计区间
（1 fork、3 次测量，部分场景方差较高），仅用于识别昂贵操作，不代表生产 SLA、回归阈值或跨后端排名。参见
[完整基准测试报告](https://github.com/Ahoo-Wang/Wow/blob/main/wow-benchmarks/results/reports/snapshot-elements.md)。

## 查询服务注册器

`SnapshotQueryServiceRegistrar` 会把本地聚合根查询服务注册到 Spring 容器。Bean 名称为 `聚合根名称 + ".SnapshotQueryService"`。

```kotlin
class OrderService(
    private val queryService: SnapshotQueryService<OrderState>,
) {
    fun getById(id: String): Mono<OrderState> = singleQuery {
        filter {
            "aggregateId" eq id
        }
    }.query(queryService).toState().throwNotFoundIfEmpty()
}
```
