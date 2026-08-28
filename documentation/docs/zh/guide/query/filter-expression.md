---
title: 过滤条件
description: 使用 FilterExpression、JSON 表达式和 Kotlin DSL 构造可组合的查询条件。
---

# 过滤条件

`FilterExpression` 是当前过滤合同。JSON 以 `op` 判别过滤器类型；嵌套的过滤器也必须使用 `op`。逻辑字段是点分路径，每一段须匹配 `[A-Za-z_][A-Za-z0-9_-]*`（首段和后续段可带 `@`，后续段也可为数组下标）。

## FilterExpression 结构

每个过滤器都是一个对象。字段过滤器使用 `field`，值使用 `value`、`values` 或范围边界；逻辑过滤器使用非空的 `operands`。

```json
{
  "op": "AND",
  "operands": [
    { "op": "EQ", "field": "state.status", "value": "PAID" },
    { "op": "GTE", "field": "state.total", "value": 100 }
  ]
}
```

`EQ` 和 `NE` 的 HTTP JSON 值必须是标量；范围、集合值和 JSON 的其他标量限制由各过滤器的规范形状定义。空 `AND`、`OR`、`NOR` 无效。

## 逻辑与常量操作符

| 操作符 | JSON 形状 | Kotlin DSL |
| --- | --- | --- |
| `MATCH_ALL` / `MATCH_NONE` | `{ "op": "MATCH_ALL" }` | `matchAll()` / `matchNone()` |
| `AND` / `OR` / `NOR` | `{ "op": "AND", "operands": [ ... ] }` | `and { ... }` / `or { ... }` / `nor { ... }` |

一个 `filterExpression { ... }` 块中并列的表达式会构成隐式 `AND`；需要改变组合语义时使用显式 `and`、`or` 或 `nor`。

## 标识与租户操作符

| 操作符 | JSON 形状 | Kotlin DSL |
| --- | --- | --- |
| `ID` / `IDS` | `{ "op": "ID", "value": "..." }` / `{ "op": "IDS", "values": ["..."] }` | `id("...")` / `ids("...")` |
| `AGGREGATE_ID` / `AGGREGATE_IDS` | `{ "op": "AGGREGATE_ID", "value": "..." }` | `aggregateId("...")` / `aggregateIds("...")` |
| `TENANT_ID` / `OWNER_ID` / `SPACE_ID` | `{ "op": "TENANT_ID", "value": "..." }` | `tenantId("...")` / `ownerId("...")` / `spaceId("...")` |

系统标识、租户、owner 与 space 必须使用这些专用操作符，不要手写看似等价的字段路径来绕过它们的语义。

## 比较与字符串操作符

| 操作符 | JSON 形状 | Kotlin DSL |
| --- | --- | --- |
| `EQ` / `NE` | `{ "op": "EQ", "field": "state.status", "value": "PAID" }` | `"status" eq "PAID"` / `"status" ne "CANCELLED"` |
| `GT` / `GTE` / `LT` / `LTE` | `{ "op": "GTE", "field": "state.total", "value": 100 }` | `"total" gte 100` |
| `CONTAINS` / `STARTS_WITH` / `ENDS_WITH` | `{ "op": "CONTAINS", "field": "state.note", "value": "vip", "stringComparison": "CASE_INSENSITIVE" }` | `"note".contains("vip", StringComparison.CASE_INSENSITIVE)` |

字符串比较默认 `CASE_SENSITIVE`。比较和字符串能力由后端及其发布的 Schema 决定。

## 集合与存在性操作符

| 操作符 | JSON 形状 | Kotlin DSL |
| --- | --- | --- |
| `IN` / `NOT_IN` | `{ "op": "IN", "field": "state.status", "values": ["PAID", "SHIPPED"] }` | `"status" isIn listOf("PAID", "SHIPPED")` |
| `BETWEEN` | `{ "op": "BETWEEN", "field": "state.total", "lowerBound": 100, "upperBound": 200 }` | `"total".between(100, 200)` |
| `CONTAINS_ALL` | `{ "op": "CONTAINS_ALL", "field": "state.tags", "values": ["vip", "new"] }` | `"tags" containsAll listOf("vip", "new")` |
| `IS_EMPTY` | `{ "op": "IS_EMPTY", "field": "state.items" }` | `"items".isEmptyCollection()` |
| `IS_NULL` / `IS_NOT_NULL` | `{ "op": "IS_NULL", "field": "state.note" }` | `"note".isNull()` / `"note".isNotNull()` |
| `EXISTS` / `NOT_EXISTS` | `{ "op": "EXISTS", "field": "state.note" }` | `"note".exists()` / `"note".notExists()` |

`IN`、`NOT_IN` 与 `CONTAINS_ALL` 的 `values` 不能为空；`BETWEEN` 的两个边界不可为 `null`。

## 数组元素匹配

`ELEMENT_MATCH` 要求同一个数组元素满足其 `predicate`。谓词中的字段以元素为根，不是数组的完整路径：

```json
{
  "op": "ELEMENT_MATCH",
  "field": "state.items",
  "predicate": { "op": "GT", "field": "quantity", "value": 1 }
}
```

```kotlin
"items".elementMatch {
    "quantity" gt 1
}
```

元素谓词不能包含 root-only 的 `ID`、`IDS`、`AGGREGATE_ID`、`AGGREGATE_IDS`、`TENANT_ID`、`OWNER_ID`、`SPACE_ID`、`DELETION` 或 `SEARCH`，即使它们嵌套在 `AND`、`OR`、`NOR` 或另一个 `ELEMENT_MATCH` 中。

## 删除标记与全文搜索

| 操作符 | JSON 形状 | Kotlin DSL |
| --- | --- | --- |
| `DELETION` | `{ "op": "DELETION", "state": "ACTIVE" }` | `deletion(DeletionState.ACTIVE)` |
| `SEARCH` | `{ "op": "SEARCH", "query": "wireless", "fields": ["state.note"], "mode": "TERMS" }` | `search("wireless", "note")` |

删除标记使用 `DELETION`，不要以字段路径模拟。快照查询默认追加 `DELETION = ACTIVE`；事件流查询保留完整历史，不追加该 guard。`SEARCH` 的 `query` 不可为空，`mode` 为 `TERMS` 或 `PHRASE`；可搜索字段、分词和结果语义取决于后端。

## 相对时间操作符

| 操作符 | JSON 形状 | Kotlin DSL |
| --- | --- | --- |
| `TODAY` / `YESTERDAY` / `BEFORE_TODAY` / `TOMORROW` | `{ "op": "TODAY", "field": "state.createTime", "zoneId": "Asia/Shanghai", "timeUnit": "MILLISECONDS" }`；`BEFORE_TODAY` 另有 `time` | `"createTime".today()` / `.yesterday()` / `.beforeToday(LocalTime.NOON)` / `.tomorrow()` |
| `THIS_WEEK` / `NEXT_WEEK` / `LAST_WEEK` | `{ "op": "THIS_WEEK", "field": "state.createTime" }` | `"createTime".thisWeek()` / `.nextWeek()` / `.lastWeek()` |
| `THIS_MONTH` / `NEXT_MONTH` / `LAST_MONTH` | `{ "op": "THIS_MONTH", "field": "state.createTime" }` | `"createTime".thisMonth()` / `.nextMonth()` / `.lastMonth()` |
| `LAST_YEAR` / `THIS_YEAR` / `NEXT_YEAR` | `{ "op": "THIS_YEAR", "field": "state.createTime" }` | `"createTime".lastYear()` / `.thisYear()` / `.nextYear()` |
| `RECENT_DAYS` / `EARLIER_DAYS` | `{ "op": "RECENT_DAYS", "field": "state.createTime", "days": 7 }` | `"createTime".recentDays(7)` / `.earlierDays(7)` |

可选 `zoneId`、`datePattern` 与 `timeUnit` 适用于相对时间过滤器；默认 `timeUnit` 是 `MILLISECONDS`，配置 `datePattern` 时忽略它。`RECENT_DAYS` 和 `EARLIER_DAYS` 的 `days` 至少为 `1`。时区、日期格式和物理时间字段能力仍由 Schema 与后端确定。

## JSON 与 Kotlin DSL 对照

下列快照查询在同一逻辑 `AND` 中限定租户、状态和数组元素数量：

```json
{
  "op": "AND",
  "operands": [
    { "op": "TENANT_ID", "value": "tenant-a" },
    { "op": "EQ", "field": "state.status", "value": "PAID" },
    {
      "op": "ELEMENT_MATCH",
      "field": "state.items",
      "predicate": { "op": "GT", "field": "quantity", "value": 1 }
    }
  ]
}
```

```kotlin
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.snapshot.pathState

val filter = filterExpression {
    tenantId("tenant-a")
    pathState {
        "status" eq "PAID"
        "items".elementMatch {
            "quantity" gt 1
        }
    }
}
```

`pathState` 将内部字段补为 `state.*`，而 `items.elementMatch` 创建独立的单元素作用域，所以 `quantity` 不会被补成 `state.items.quantity`。`path { ... }` 内的多个表达式同样形成一个隐式 `AND`。

## 字段路径规则

`field` 是逻辑路径，不是任意后端的物理字段名。根路径取决于查询模型：快照的业务字段位于 `state`；事件流的根字段与展开后的事件字段不同，事件 payload 位于 `body.body`。因此不要把快照的 `state.*` 路径复制到事件流查询，也不要从物理 mapping 猜测逻辑字段。

`path` 只做词法路径作用域：在 `"state".path { "status" eq "PAID" }` 中得到 `state.status`；已以当前前缀开头的路径保持不变。`elementMatch` 则建立独立元素作用域，谓词字段相对元素。

## 安全与兼容边界

查询模型 Schema 负责把逻辑字段解析为后端已证明的能力；请参阅[查询总览中的 Schema 说明](./query-model-schema.md)。MongoDB、Elasticsearch 或自定义后端可以支持不同的比较、存在性、全文搜索或时间语义，公共操作符列表不承诺跨后端一致性。

HTTP 请求在 WebFlux `ServerRequest` context 中会经过 `HttpQueryGuardFilter`。`wow.webflux.query.allow-expensive-operators=false` 时，会拒绝 `NE`、`NOT_IN`、`NOR`、`IS_NULL`、`IS_NOT_NULL`、`NOT_EXISTS`、`IS_EMPTY`、`CONTAINS`、`ENDS_WITH`，以及空字符串或大小写不敏感的 `STARTS_WITH`；HTTP guard 还限制 filter 节点和值数量。该配置的兼容默认值不是容量证明，详见[基础设施配置](../../reference/config/infrastructure)。进程内查询不因这项 HTTP 专用保护而获得或失去后端能力。

`Condition`、`Operator`、`ConditionDsl` 仍是已弃用兼容输入；新代码使用 `FilterExpression` 和 `FilterDsl`。兼容反序列化可接受旧 `operator` 形状，但 `op` 与 `operator` 不能同时出现；规范 JSON 和 OpenAPI 只发布 `op`。
