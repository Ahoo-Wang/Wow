---
title: V10 查询语言
description: Wow V10 服务的筛选条件、组合子、字段路径、查询种类、分页、游标、能力描述、Kotlin DSL、生成的字段常量与查询注解。
outline: deep
---

# V10 查询语言

V10 服务在 `POST /<context>/<aggregate>/<model>/query` 上回答其读模型的查询（见 [HTTP API](./http-api.md#查询)）。本页是请求体所带查询的参考：筛选条件、排序、投影与分页，每种查询的请求与响应，能力描述，在进程内构建同样查询的 Kotlin DSL，以及调整快照模型的注解。

协议是 `wow-query-api`（`me.ahoo.wow.api.query`）中的纯数据；`wow-query` 负责它的 JSON 读写。下面的片段都是 `snapshot` 模型的请求体。

## 一个请求

请求体是一个 JSON 对象：`kind` 指明操作，其余属性是该种查询本身。

```json
{
  "kind": "paged",
  "filter": {
    "op": "AND",
    "operands": [
      { "op": "EQ", "field": "state.categoryId", "value": "books" },
      { "op": "BETWEEN", "field": "state.price", "lowerBound": 10, "upperBound": 50 }
    ]
  },
  "sort": [{ "field": "state.price", "direction": "ASC" }],
  "pagination": { "index": 1, "size": 20 }
}
```

该种查询没有的属性返回 400 `wow.query.malformed`，违例码为 `UNKNOWN_PROPERTY`；缺少 `kind` 或取值不对时为 `INVALID_VALUE` 或 `UNKNOWN_VALUE`。

## 跨租户查询

查询读取调用方所在的租户。带 `crossScope` 的调用方可以在查询路由上用 `Wow-Scope` 指定其他租户：`tenant=*` 表示所有租户，或者用内部列表 `tenant=("t1" "t2")`。在 `wow.query.cross-tenant.enabled` 打开之前，这一功能是关闭的，关闭时这类查询返回 403 `wow.auth.forbidden`。

- 租户固定的聚合类型仍然只读它自己的租户。列表里不含该租户时返回 400 `wow.query.malformed`。
- 每次跨租户查询都会以 INFO 级别记录到 `me.ahoo.wow.query.CrossTenantAudit`，无论是否有查询观察者做审计。
- 命令与状态读从不跨租户：在那里 `tenant` 为 `*` 或列表时返回 400。
- 进程内代码用 `QueryApi.trusted()` 读取所有租户。

## 字段路径

字段以逻辑路径命名，即用 `.` 连接各段的 JSON 字符串：`state.address.city`。每段以字母或 `_` 开头，由字母、数字、`_` 与 `-` 组成；第一段之后的段也可以是数组下标（`state.lines.0`）。

快照模型的记录包含系统字段，以及位于 `state` 下的状态：

| 字段 | 类型 | 内容 |
| --- | --- | --- |
| `tenantId` | 字符串 | 聚合的租户 |
| `aggregateId` | 字符串 | 聚合 ID，在租户内唯一；记录的标识 |
| `version` | 整数 | 事件数 |
| `ownerId`、`spaceId` | 字符串或 `null` | 所有者与空间 |
| `deleted` | 布尔 | 是否已删除 |
| `tags` | 字符串数组组成的对象 | 标签；`tags.<key>` 指其中一个 |
| `firstEventTime`、`lastEventTime` | 整数 | 第一个与最后一个事件的时间，epoch 毫秒 |
| `operator`、`firstOperator` | 对象或 `null` | 最后一个与第一个命令的操作人：`{id, tenantId, crossScope}`；匿名调用方为 `null`。按 `operator.id` 筛选 |
| `state.*` | 以状态 Schema 为准 | 状态，按补全后的 Manifest 片段中的状态 Schema 描述 |

模型不认识的路径返回 400 `wow.query.invalid`，`UNKNOWN_FIELD`。集合元素的字段只能在该集合的 `ELEMENT_MATCH` 内、相对元素命名（否则为 `ELEMENT_SCOPE_REQUIRED`）。[能力描述](#能力描述)列出每条路径及其可用的操作符、排序与聚合。

`events` 模型读取一个聚合的事件流：筛选条件是以下各项的 `AND`：该聚合（`aggregateId` 的 `EQ`，或 `ID` / `AGGREGATE_ID`），可选的 `version` 与 `commitTime` 范围（`EQ`、`GT`、`GTE`、`LT`、`LTE`、`BETWEEN`；时间为 epoch 毫秒或 ISO-8601 时刻），此外不能有别的。它只按 `version` 升序排序，不接受投影，也没有 `aggregate`。每条记录是一个事件流：`aggregateId`、`tenantId`、`version`、`commitTime`、`commandId`、`requestId`、`commandType`，有值时的 `ownerId` 与 `spaceId`，已知时的 `operator`，`tags`（该次提交后聚合的标签，日志记录标签之前追加的流为 `null`），以及 `events`，每个为 `{name, revision, body}`。启用 ABAC 时每个事件流按自己的 `tags` 过滤，时间线按提交时聚合的标签显示该流。

## 筛选条件

筛选条件是一棵节点树。每个节点是一个 JSON 对象，`op` 指明其操作符，该操作符的属性与之并列。没有 `op` 的对象（9.x 的 `Condition`）会被拒绝。

### 值

值是 JSON 标量：字符串、数字、布尔，或操作符允许处的 `null`。数字保持精度：`1.50` 仍是小数，超过 2^53 的整数也是精确的。值必须与状态 Schema 中字段的类型一致（否则为 `VALUE_MISMATCH`）：字符串字段与字符串比较，数字字段与数字比较。时间字段（`Instant`、`LocalDate`，或带 `@QueryTemporal` 的整数、字符串）以其自身的编码比较：`Instant` 为 epoch 毫秒，`LocalDate` 为 `"2026-10-13"`。

### 比较

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `EQ` | `field`，`value`：标量或 `null` | 字段等于 `value`；`null` 表示字段是否为空 |
| `NE` | `field`，`value` | 字段不等于 `value` |
| `GT`、`GTE`、`LT`、`LTE` | `field`，`value`：标量，不能为 `null` | 字段大于、大于等于、小于、小于等于 |
| `BETWEEN` | `field`，`lowerBound`、`upperBound`：标量，不能为 `null` | `lowerBound <= field <= upperBound`，两端都包含 |
| `IN` | `field`，`values`：非空的标量数组，不含 `null` | 字段等于 `values` 之一 |
| `NOT_IN` | `field`，`values` | 字段不等于 `values` 中的任何一个 |

```json
{ "op": "IN", "field": "state.status", "values": ["PAID", "SHIPPED"] }
{ "op": "BETWEEN", "field": "state.price", "lowerBound": 10, "upperBound": 50 }
```

`IN` 用 `values` 而不是 `value`；`BETWEEN` 用 `lowerBound` 与 `upperBound`，而不是数组。

### 字符串

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `CONTAINS` | `field`，`value`：字符串，`stringComparison` | 字段包含 `value` |
| `STARTS_WITH` | `field`，`value`，`stringComparison` | 字段以 `value` 开头 |
| `ENDS_WITH` | `field`，`value`，`stringComparison` | 字段以 `value` 结尾 |
| `IS_EMPTY_STRING` | `field` | 字段为 `""` |
| `IS_NOT_EMPTY_STRING` | `field` | 字段是 `""` 以外的字符串 |

`stringComparison` 为 `CASE_SENSITIVE`（默认）或 `CASE_INSENSITIVE`。

### 集合

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `CONTAINS_ALL` | `field`，`values`：非空的标量数组 | 数组字段包含 `values` 的每一个 |
| `IS_EMPTY` | `field` | 数组字段为空 |
| `ELEMENT_MATCH` | `field`，`predicate`：筛选条件 | 数组字段的某个元素匹配 `predicate`，其中的字段相对元素 |

```json
{
  "op": "ELEMENT_MATCH",
  "field": "state.lines",
  "predicate": {
    "op": "AND",
    "operands": [
      { "op": "EQ", "field": "sku", "value": "B" },
      { "op": "GTE", "field": "quantity", "value": 2 }
    ]
  }
}
```

谓词匹配的是同一个元素：两个条件在同一行上成立。它不能包含系统字段筛选，也不能包含针对整个模型的 `SEARCH`。

### 存在性

缺失的字段与显式的 `null` 都算空；只有缺失的字段不存在。带值的比较、字符串操作符与范围都不匹配空字段，而 `NE` 与 `NOT_IN` 匹配。对数组字段，标量操作数匹配任一元素；以数组为操作数的 `EQ`（仅进程内）匹配整个数组。

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `IS_NULL` | `field` | 字段为 `null` 或缺失 |
| `IS_NOT_NULL` | `field` | 字段有值 |
| `EXISTS` | `field` | 字段存在，包括 `null` |
| `NOT_EXISTS` | `field` | 字段缺失 |

### 相对时间

每个都接受 `field`，以及可选的 `zoneId`（IANA 时区，如 `Asia/Shanghai`；默认为服务器时区）、`datePattern`（`DateTimeFormatter` 模式，用于以格式化字符串存储的字段）与 `timeUnit`（默认 `MILLISECONDS`，或其他 `TimeUnit` 名称，用于以 epoch 数字存储的字段）。

| `op` | 其他属性 | 匹配 |
| --- | --- | --- |
| `TODAY`、`YESTERDAY`、`TOMORROW` | | 那一天 |
| `THIS_WEEK`、`LAST_WEEK`、`NEXT_WEEK` | | 那一周 |
| `THIS_MONTH`、`LAST_MONTH`、`NEXT_MONTH` | | 那一月 |
| `THIS_YEAR`、`LAST_YEAR`、`NEXT_YEAR` | | 那一年 |
| `BEFORE_TODAY` | `time`：`"18:00"` 或 `"18:00:30"` | 今天该时刻之前 |
| `RECENT_DAYS` | `days`：整数 | 最近 `days` 天内，含今天 |
| `EARLIER_DAYS` | `days` | 最近 `days` 天之前 |
| `BEFORE_NOW`、`AFTER_NOW` | `offset`：ISO-8601 时长，默认 `PT0S` | 在服务器当前时间加 `offset` 之前或之后（`-PT30M` 回看半小时） |

```json
{ "op": "RECENT_DAYS", "field": "lastEventTime", "days": 7, "zoneId": "Asia/Shanghai" }
```

### 系统字段

这些操作符不带 `field`：每个筛选模型定义的一个系统字段。

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `ID` | `value`：字符串 | 记录的标识：快照模型上为 `aggregateId` |
| `IDS` | `values`：非空的字符串数组 | 其中之一 |
| `AGGREGATE_ID`、`AGGREGATE_IDS` | `value` / `values` | 聚合 ID |
| `TENANT_ID`、`OWNER_ID`、`SPACE_ID` | `value` | 租户、所有者、空间 |
| `DELETION` | `state`：`ACTIVE`、`DELETED` 或 `ALL` | 处于该删除状态的聚合 |
| `MATCH_ALL`、`MATCH_NONE` | | 所有记录、没有记录 |

快照查询只看到未删除的聚合，除非筛选条件自己给出 `DELETION`。调用方的租户、所有者与空间总会追加上去（见 [HTTP API](./http-api.md#查询)）；筛选条件无法放宽它们。

### 搜索与表达式

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `SEARCH` | `query`：非空字符串，`fields`：路径（不给则为所有可搜索字段），`mode`：`TERMS`（默认）或 `PHRASE` | 全文匹配，需后端支持（否则为 `MODEL_SEARCH_UNSUPPORTED`） |
| `EXPRESSION` | `expression`，`comparison`：`EQ`、`NE`、`GT`、`GTE`、`LT`、`LTE`，`value`：有限数 | 每条记录计算出的 `expression` 满足该比较；算不出值的记录不匹配 |

`expression` 是 `{"type":"FIELD","field":…}`、`{"type":"CONSTANT","value":…}`、`{"type":"BINARY","operator":"ADD|SUBTRACT|MULTIPLY|DIVIDE","left":…,"right":…}` 与 `{"type":"DATE_DIFF","from":…,"to":…,"unit":"SECOND|MINUTE|HOUR|DAY"}` 之一：

```json
{
  "op": "EXPRESSION",
  "expression": { "type": "DATE_DIFF", "from": "state.paidAt", "to": "state.shippedAt", "unit": "HOUR" },
  "comparison": "GT",
  "value": 24
}
```

### 组合子

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `AND` | `operands`：非空的筛选条件数组 | 每个操作数 |
| `OR` | `operands` | 至少一个操作数 |
| `NOR` | `operands` | 没有任何操作数 |

没有 `NOT`：单个操作数的 `NOR` 即为取反。

```json
{ "op": "NOR", "operands": [{ "op": "EQ", "field": "state.status", "value": "CANCELLED" }] }
```

### 限制与开销

经 HTTP 时，一个筛选条件最多 `wow.query.http.max-filter-nodes` 个节点（128）、`max-filter-values` 个值（1000）（`FILTER_TOO_LARGE`）。有些操作符用不上索引：`NOR`、`NE`、`NOT_IN`、`CONTAINS`、`ENDS_WITH`、忽略大小写或为空的 `STARTS_WITH`、`IS_EMPTY`、`IS_NOT_EMPTY_STRING`、`IS_NULL`、`IS_NOT_NULL`、`NOT_EXISTS` 与 `EXPRESSION`。设置 `wow.query.http.allow-expensive-operators: false` 时它们会被拒绝（`EXPENSIVE_OPERATOR_DISABLED`）。

## 排序与投影

`sort` 是 `{"field": …, "direction": "ASC" | "DESC"}` 的数组，最多 32 项，每个字段一次。字段只在能力描述允许处排序（`sort.paged`、`sort.cursor`）。

`projection` 为 `{"include": [路径], "exclude": [路径]}`；两者都没有时返回完整记录。包含的路径连同其后代一起返回。

```json
{ "kind": "list", "filter": { "op": "MATCH_ALL" }, "projection": { "include": ["aggregateId", "state.name"] }, "limit": 50 }
```

## 查询种类

| `kind` | 请求属性 | 响应 |
| --- | --- | --- |
| `single` | `filter`、`projection`、`sort` | 按 `sort` 的第一条记录；没有匹配时 404 `wow.aggregate.not-found` |
| `list` | `filter`、`projection`、`sort`、`limit` | 记录的 JSON 数组；`Accept: text/event-stream` 时为服务器发送事件 |
| `paged` | `filter`、`projection`、`sort`、`pagination`、`total` | `{"total": …, "exact": …, "list": [记录]}` |
| `cursor` | `filter`、`projection`、`sort`、`size`、`cursor` | `{"list": [记录], "nextCursor": …}` |
| `count` | `filter`、`limit` | `{"count": …, "exact": …}` |
| `aggregate` | `filter`、`elements`、`groupBy`、`metrics`、`sort`、`limit`、`having` | 行的 JSON 数组 |

除 `aggregate`（默认 `MATCH_ALL`）外，每种查询都必须给出 `filter`；`{"op":"MATCH_ALL"}` 匹配所有记录。

### `list`

`limit` 是返回记录的上限。`0` 或不给时取 `wow.query.http.default-list-size`（100），最多允许 `max-list-size`（1000）（`SIZE_OUT_OF_RANGE`）。`Accept: text/event-stream` 时每条记录是一个 `row` 事件（`id` 为从 0 起的序号）；第一条之后发生的失败以一个携带 problem 的 `error` 事件结束流。

### `paged`

`pagination` 默认为 `{"index": 1, "size": 10}`：`index` 从 1 起，`size` 最多 `max-page-size`（100），`index × size` 最多 `max-page-window`（10000）。`total` 决定总数的计法：

- `BOUNDED`（默认）：最多数到 `wow.query.http.count-limit`（10000）。超过时 `exact` 为 false，`total` 为该上限，读作「至少」（`10000+`）。
- `EXACT`：精确计数；经 HTTP 时须打开 `wow.query.http.allow-exact-count`，否则被拒绝。
- `NONE`：不计数；`total` 为 `null`，`exact` 为 false。

```json
{ "total": 10000, "exact": false, "list": [{ "aggregateId": "p-1", "...": "..." }] }
```

### `cursor`

游标页是 `cursor`（上一页的 `nextCursor`；第一页没有）之后的 `size` 条记录（默认 10，最多 `max-page-size`）。服务端会把记录的唯一键追加到 `sort`（快照模型上为 `tenantId`、`aggregateId`），使顺序全序，翻页既不重复也不遗漏。最后一页的 `nextCursor` 为 `null`。游标是不透明的，属于它的模型与排序：带上它时要用相同的排序与筛选条件（排序不同即 `INVALID_CURSOR`）。无限滚动用游标；它从不计数。

```json
{ "kind": "cursor", "filter": { "op": "MATCH_ALL" }, "sort": [{ "field": "lastEventTime", "direction": "DESC" }], "size": 20 }
```

```json
{ "list": [{ "aggregateId": "o-9", "...": "..." }], "nextCursor": "eyJ…" }
```

### `count`

`limit` 至少为 1，是计数的上限；不给时取 `count-limit`。计数停在上限时 `exact` 为 false。计数用于带筛选、有上限的数目；固定维度的统计（如各状态的订单数）应由投影维护计数器或走数据集。

### `aggregate`

聚合对匹配的记录分组，并按组计算指标。`limit` 是组数上限（默认 100）。

```json
{
  "kind": "aggregate",
  "filter": { "op": "NE", "field": "state.status", "value": "CANCELLED" },
  "groupBy": [{ "type": "TERMS", "field": "state.status", "alias": "status" }],
  "metrics": [
    { "type": "COUNT", "alias": "count" },
    { "type": "NUMERIC", "function": "SUM", "expression": { "type": "FIELD", "field": "state.amount" }, "alias": "amount" }
  ],
  "sort": [{ "field": "count", "direction": "DESC" }]
}
```

```json
[{ "status": "PAID", "count": 2, "amount": 30.5 }, { "status": "NEW", "count": 1, "amount": 30.0 }]
```

每行包含分组别名与指标别名。

- **`elements`**：`[{"path": "state.lines", "filter": …}]` 展开一个集合，使分组与指标作用于其元素（最多 5 层）；此时字段相对最内层元素，带 `"scope": "ROOT"` 的分组则读取记录的绝对路径。
- **`groupBy`**：`TERMS`（`field` 或 `expression`，可选 `missingKey`）、`HISTOGRAM`（`field` 或 `expression`，`interval`）、`DATE_HISTOGRAM`（`field`，`unit`：`YEAR`、`QUARTER`、`MONTH`、`WEEK`、`DAY`、`HOUR`、`MINUTE`、`SECOND`，`timeZone`，`dense`）与 `DATE_PART`（`field`，`part`：`DAY_OF_WEEK`、`DAY_OF_MONTH`、`HOUR_OF_DAY`、`MONTH_OF_YEAR`，`timeZone`，`dense`）；每个都有 `alias`。`dense` 补齐空桶，要求该分组是唯一的分组。
- **`metrics`**：`COUNT`、`NUMERIC`（`function`：`SUM`、`AVG`、`MIN`、`MAX`、`STDDEV`、`VARIANCE`，`expression`）、`DISTINCT_COUNT`（`expression`）、`PERCENTILE`（`expression`、`percentile`）、`ANY`、`FIRST` 与 `LAST`（`field`，`orderBy`，默认 `lastEventTime`），以及 `DERIVED`（由其他指标算出的 `expression`：`METRIC_REF`、`CONSTANT`、`BINARY`、`TOTAL`、`RUNNING`、`PREVIOUS`、`RANK`）。除 `DERIVED` 外都可带可选的 `filter`，只统计匹配的部分。
- **`sort`** 引用分组与指标的别名；未列出的分组在其后升序排列。
- **`having`** 保留指标满足条件的组：`CONDITION`（`metric`、`operator`、`value`）、`BETWEEN`（`metric`、`lower`、`upper`）、`IN`（`metric`、`values`）、`IS_NULL`（`metric`、`negated`）、`AND` 与 `OR`（`operands`）。

能力描述的 `analysis` 说明后端支持其中哪些。`events` 模型没有聚合。

## 能力描述

`GET /<context>/<aggregate>/<model>/query` 按调用方的入口说明模型接受什么：

```json
{
  "model": "SNAPSHOT",
  "version": "sha256:b3c7…",
  "timeZone": "UTC",
  "record": { "identity": "aggregateId", "paging": ["LIST", "PAGED", "CURSOR"], "defaultScope": "ACTIVE",
              "rootOperators": ["ID", "IDS", "AGGREGATE_ID", "AGGREGATE_IDS", "TENANT_ID", "OWNER_ID", "SPACE_ID", "DELETION", "EXPRESSION"] },
  "limits": { "maxListSize": 1000, "defaultListSize": 100, "maxPageSize": 100, "maxPageWindow": 10000, "maxFilterNodes": 128, "...": "..." },
  "analysis": { "metrics": ["COUNT", "NUMERIC", "..."], "...": "..." },
  "fields": [
    { "path": "aggregateId", "types": ["STRING"], "kind": "SCALAR", "nullable": false, "project": true,
      "filter": { "operators": ["EQ", "NE", "GT", "..."] }, "sort": { "paged": true, "cursor": true },
      "aggregate": { "groups": ["TERMS"], "...": "..." }, "aliases": [] }
  ],
  "elements": [{ "path": "state.lines", "filter": true, "aggregate": true }],
  "dynamic": [{ "pattern": "tags.{key}", "types": ["STRING"], "kind": "ARRAY", "filter": { "operators": ["EQ", "..."] } }],
  "constraints": [{ "type": "CURSOR_UNIQUE_SORT", "appended": "aggregateId", "fields": ["tenantId", "aggregateId"] }]
}
```

- `fields` 列出每条路径：值类型、其 `filter` 接受的操作符、能否在分页与游标中排序、如何分组与聚合。`elements` 是 `ELEMENT_MATCH` 或 `elements` 项能进入的集合，`dynamic` 是映射键的模式，如 `tags.{key}`。
- `record.rootOperators` 是不带字段的操作符；组合子 `AND`、`OR`、`NOR` 处处可用。
- `version` 是能力描述的摘要。响应以它为 `ETag`；带 `If-None-Match: "<version>"` 的 `GET` 在描述不变时返回 304，客户端可以缓存描述，再问的代价很小。

## 错误

| 错误码 | 状态 | 何时 |
| --- | --- | --- |
| `wow.query.malformed` | 400 | 请求体无法读成查询：`EMPTY_BODY`、`BODY_NOT_OBJECT`、`INVALID_JSON`、`UNKNOWN_PROPERTY`、`UNKNOWN_TYPE`（未知的 `op` 或 `type`）、`UNKNOWN_VALUE`、`INVALID_VALUE`、`INVALID_REQUEST`（查询类型自身的规则，如 `operands` 为空） |
| `wow.query.invalid` | 400 | 模型拒绝；每条违例指出其规则，如 `UNKNOWN_FIELD`、`UNSUPPORTED_CAPABILITY`、`VALUE_MISMATCH`、`ELEMENT_SCOPE_REQUIRED`、`SIZE_OUT_OF_RANGE`、`FILTER_TOO_LARGE`、`EXPENSIVE_OPERATOR_DISABLED`、`INVALID_CURSOR`、`STORAGE_UNSUPPORTED` |
| `wow.query.failed` | 500 | 后端失败 |
| `wow.query.timeout` | 503 | 查询执行超过 `wow.query.http.timeout` |

problem 的 `violations` 中每条违例有 `path`（请求体中的 JSON 路径，如 `filter.operands`）、`code` 与 `message`。

## Kotlin DSL

`wow-query-api` 带有一套 Kotlin DSL（`me.ahoo.wow.api.query.dsl`），构建的查询对象与 JSON 读出的相同：用它构建的查询就是其 JSON 读成的查询，进程内调用方（`QueryGateway`）与 HTTP 客户端用的是同一种语言。

```kotlin
import me.ahoo.wow.api.query.dsl.pagedQuery

val query = pagedQuery {
    filter {
        "state.categoryId" eq "books"
        "state.price" between 10..50
        not { "state.status" eq "DISCONTINUED" }
    }
    sort { "state.price".asc() }
    pagination {
        index(1)
        size(20)
    }
}
```

| 构建器 | 构建 | 可设置 |
| --- | --- | --- |
| `singleQuery { }` | `SingleQuery` | `filter`、`projection`、`sort` |
| `listQuery { }` | `ListQuery` | 同上，`limit(n)` |
| `pagedQuery { }` | `PagedQuery` | 同上，`pagination { index(); size() }`、`total(TotalMode.EXACT)` |
| `cursorQuery { }` | `CursorQuery` | 同上，`size(n)`、`cursor(next)` |
| `countQuery { }` | `CountQuery` | `filter`、`limit(n)` |
| `aggregation { }` | `AggregationQuery` | `filter`、`expand`、分组、指标、`sort`、`limit`、`having` |
| `filter { }` | `FilterExpression` | 单独构建，供多个查询复用 |

在 `filter { }` 中每次调用添加一个节点，块中的节点以 `AND` 组合。每个字段操作符都接受字符串路径或 `QueryField`，例如[生成的常量](#生成的字段常量)：

| DSL | 线上形式 |
| --- | --- |
| `"f" eq v`、`"f" ne v` | `EQ`、`NE`；`eq null` 为 `IS_NULL`，`ne null` 为 `IS_NOT_NULL` |
| `"f" gt v`、`gte`、`lt`、`lte` | `GT`、`GTE`、`LT`、`LTE` |
| `"f".between(a, b)`、`"f" between a..b` | `BETWEEN` |
| `"f" isIn list`、`"f" notIn list`、`"f" containsAll list` | `IN`、`NOT_IN`、`CONTAINS_ALL` |
| `"f".containsText(s)`、`startsWithText(s)`、`endsWithText(s)`，均可带 `StringComparison` | `CONTAINS`、`STARTS_WITH`、`ENDS_WITH` |
| `"f".isNull()`、`isNotNull()`、`exists()`、`notExists()` | `IS_NULL`、`IS_NOT_NULL`、`EXISTS`、`NOT_EXISTS` |
| `"f".isEmptyCollection()`、`isEmptyString()`、`isNotEmptyString()` | `IS_EMPTY`、`IS_EMPTY_STRING`、`IS_NOT_EMPTY_STRING` |
| `"f".today()`、`recentDays(7)`、`beforeToday(LocalTime.NOON)`、`afterNow(Duration.ofMinutes(-30))`…… | 相对时间操作符，可带 `zoneId`、`datePattern`、`timeUnit` |
| `"f".elementMatch { … }` | `ELEMENT_MATCH`，块内相对元素 |
| `search("q", mode, fields)`、`"f" search "q"` | `SEARCH` |
| `expression(expr, ComparisonOperator.GT, 24.0)` | `EXPRESSION` |
| `id`、`ids`、`aggregateId`、`aggregateIds`、`tenantId`、`ownerId`、`spaceId`、`deletion(DeletionState.ALL)`、`matchAll()`、`matchNone()` | 系统字段操作符 |
| `and { }`、`or { }`、`nor { }`、`not { }` | `AND`、`OR`、`NOR`；`not` 是其块的 `NOR` |
| `"state".path { "name" eq "x" }` | 块内的相对字段位于 `state.` 下 |

值原样上线。运行时值（如枚举）按它写成的 JSON 比较：`"state.status" eq OrderStatus.PAID` 与 `"PAID"` 查到的相同。`sort { "f".asc(); "g".desc() }` 与 `projection { include(…); exclude(…) }` 接受字符串或 `QueryField`；`nested("state")` 让字段位于某路径下。

聚合按别名命名各部分：

```kotlin
val byStatus = aggregation {
    filter { "state.status" ne "CANCELLED" }
    terms("state.status", "status")
    count("count")
    sum("state.amount", "amount")
    derived("share") { ref("amount") / total("amount") }
    sort { "count".desc() }
    having { "count" gte 1.0 }
}
```

9.x 的 DSL 在 `me.ahoo.wow.query.dsl`（`wow-query`）；V10 的在 `me.ahoo.wow.api.query.dsl`，构建器相同（去掉了 `condition { }`），另加 `countQuery { }`、`not { }`、带区间的 `between` 与 `QueryField` 接收者。

## 生成的字段常量

领域模块的编译类路径上有 `wow-query-api` 时，编译器在每个聚合的状态旁生成 `<State>Properties`：一个由 `QueryField` 常量组成的对象，快照查询能命名的每条路径一个，继承持有系统字段的 `SnapshotProperties`。

```kotlin
// 为 OrderState 生成
public object OrderStateProperties : me.ahoo.wow.api.query.SnapshotProperties() {
    public val STATE__ID = QueryField("state.id")
    public val STATE__ITEMS = QueryField("state.items")
    public val STATE__ITEMS__PRODUCT_ID = QueryField("state.items.productId")
    public val STATE__ADDRESS__CITY = QueryField("state.address.city")
    public val STATE__STATUS = QueryField("state.status")
    // ...
}
```

- **系统字段**来自 `SnapshotProperties`：`TENANT_ID`、`AGGREGATE_ID`、`VERSION`、`OWNER_ID`、`SPACE_ID`、`DELETED`、`TAGS` 与 `tag(name)`、`FIRST_EVENT_TIME`、`LAST_EVENT_TIME`、`OPERATOR`、`OPERATOR__ID`、`OPERATOR__TENANT_ID`、`OPERATOR__CROSS_SCOPE`、`FIRST_OPERATOR` 的同名各项，以及 `STATE`。没有具体状态时，直接用 `SnapshotProperties` 上的。
- **命名**：常量按其路径上的 Kotlin 属性名以大写下划线命名，嵌套段之间用 `__`；值是 Jackson 写出的路径，包括 `@JsonProperty` 的名字。
- **哪些路径**：Jackson 会写出的每个带幕后字段的公开属性（没有 `@JsonIgnore`，也不在 `@JsonIgnoreProperties` 中），深入应用类与集合的元素。映射、枚举、接口、抽象类、密封类、值类、JDK 与 Kotlin 类型，以及路径上已出现过的类都是叶子。这正是补全后片段中状态 Schema 描述的路径。
- **元素**：元素的常量（如 `STATE__ITEMS__PRODUCT_ID`）要在其集合的 `ELEMENT_MATCH` 内使用。DSL 会把位于元素自身路径下的字段读成相对元素：

```kotlin
val query = listQuery {
    filter {
        OrderStateProperties.STATE__STATUS isIn listOf(OrderStatus.PAID, OrderStatus.SHIPPED)
        OrderStateProperties.STATE__ITEMS.elementMatch {
            OrderStateProperties.STATE__ITEMS__PRODUCT_ID eq "p-1"   // 相对元素即 productId
        }
    }
    sort { OrderStateProperties.LAST_EVENT_TIME.desc() }
}
```

9.x 的 `<State>Properties` 是相对状态的 `const val` 字符串（`ITEMS__PRODUCT_ID = "items.productId"`）；V10 的是完整路径的 `QueryField`，因此 `ITEMS__PRODUCT_ID` 变为 `STATE__ITEMS__PRODUCT_ID`。

## 查询注解

`me.ahoo.wow.api.query.annotation` 的注解声明快照模型无法从状态的类型推出的信息。它们在 `wow-query-api` 中，领域模块在内核之外加上它：

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-query-api")
}
```

注解写在 Kotlin 属性上，使用 `@field:` 目标，因为 `manifest-tools` 从编译后状态的字段上读取它们。`manifest-tools` 把它们以 `x-wow-*` 关键字写进模块 Manifest 片段的状态 Schema；查询模型与能力描述从那里读取。

| 注解 | 属性 | 声明 |
| --- | --- | --- |
| `@QueryIndex` | `name`、`on`、`with` | 后端创建的索引；见下文 |
| `@QueryTemporal` | `unit`（`TimeUnit`，默认 `MILLISECONDS`）、`pattern` | 以 `unit` 为单位存放 epoch 时间的整数字段，或以 `pattern` 格式化的字符串字段；标准时间类型不需要 |
| `@QueryDecimal` | `scale` | 有 `scale` 位小数的定点数 |
| `@QueryMoney` | `currency` 或 `currencyField`、`scale` | 金额，币种为固定的 ISO 4217 币种或同级字符串字段的值 |
| `@QueryDuration` | `unit` | 以 `unit` 计的时长 |
| `@QueryReference` | `aggregateName`、`contextName` | 另一个聚合的 ID（默认在本上下文） |
| `@QueryAlias` | `value`：路径 | 查询可以用来指代该字段的其他完整路径，如改名前的名字 |
| `@Sensitive` | `level`：`DISPLAY` 或 `CONFIDENTIAL`，`mask: Mask(keepPrefix, keepSuffix, strategy)` | 在每个结果中脱敏的字符串字段；`DISPLAY` 仍可在分页中筛选与排序（`wow.query.sensitivity.display-comparable`），`CONFIDENTIAL` 从不参与比较 |

一个字段只有一种语义类型：`@QueryTemporal`、`@QueryDecimal`、`@QueryMoney`、`@QueryDuration` 与 `@QueryReference` 不能组合。

```kotlin
@QueryIndex(on = "ownerId", with = ["lastEventTime"])
class ProductState(val id: String) {
    @field:QueryIndex(with = ["state.status", "state.price"])
    var brandId: String = ""
        private set

    @field:QueryDecimal(scale = 2)
    var price: BigDecimal = BigDecimal.ZERO
        private set

    @field:QueryTemporal(unit = TimeUnit.SECONDS)
    var listedAt: Long = 0
        private set

    @field:QueryAlias("state.title")
    var name: String = ""
        private set
}
```

### `@QueryIndex`

- **在属性上**时由该属性领头（`on` 留空），`with` 依次列出其余的键。**在状态类上**时由 `on` 指定领头的键。注解可重复：一个字段可以领头多个索引。
- **键**是模型的完整逻辑路径：系统字段（`ownerId`、`lastEventTime`……）或按 JSON 名字写的 `state.<path>`。键必须支持精确匹配、范围或排序；在 PostgreSQL 上只能有一个值（数组路径不能作键），MongoDB 不能在一个索引中索引两个数组。
- **每个索引**都以租户开头（准入总会为查询追加租户），并且只覆盖未删除的快照（部分索引）。
- **`name`** 默认为空，此时自动推导：`ix_<aggregate>_<keys>`，每个键转为 snake case、点换成 `_`，如 `ix_product_state_brand_id_state_status_state_price`。超过 63 个字符时保留前 54 个，再加 `_` 与其 SHA-256 的 8 位十六进制。名字在一个限界上下文内唯一。
- **`wow.query.index.ddl`** 管理它们：`auto` 创建缺失的、重建无效的（PostgreSQL 用 `CREATE INDEX CONCURRENTLY`），`validate` 只报告，`none` 不动 schema。

**在 PostgreSQL 上**，索引是聚合快照表 `wow_snapshot__<context>__<aggregate>` 上的表达式索引，名为 `<table>_<name>`：`wow_snapshot__eshop_catalog__product_ix_product_state_brand_id_…`。这个名字同样限制在 63 个字符内（前 54 个，再加 `_` 与全名 SHA-256 的 8 位十六进制），所以长名字以哈希结尾：要把 Manifest 中的索引对到数据库，按同样规则计算 `<table>_<name>`，或比较索引定义。每个键都是 SQL 编译器为该路径生成的同一个表达式，按 Schema 定型（在 `state` 列上的 `CASE WHEN jsonb_typeof(...)`，转换为 `numeric` 或 `boolean`，文本用 `"C"` 排序规则），这样规划器才会用上索引：

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS "<table>_<name>" ON "<table>"
    (tenant_id, <key expression> NULLS FIRST, ...) WHERE NOT deleted
```

**在 MongoDB 上**，它是聚合快照集合上的复合索引 `{tenant_id: 1, <key>: 1, …}`，带 `partialFilterExpression: {deleted: false}`，沿用自己的 `name`，因为 MongoDB 的索引名按集合区分。
