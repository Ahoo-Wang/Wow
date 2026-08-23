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
