---
title: 查询语言
description: 快照、事件和数据集模型的 JSON 查询语言：字段、过滤、排序和投影，查询种类及其回答，能力描述，以及错误。
outline: deep
---

# 查询语言

服务的每个查询模型，即每个聚合类型的快照和事件，以及每个数据集，都在 `POST …/query` 上接收同样的 JSON 查询。本页是这门语言的参考。示例查询 eShop catalog 的商品，每个示例都在 catalog 服务的测试中运行过。怎样在 Kotlin 中和经 HTTP 使用它，见[查询快照](../guide/queries)。

## 请求 {#a-request}

请求体是一个 JSON 对象：`kind` 指明操作，其他属性是该种类的查询。

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/request.json -->

```json
{
  "kind": "paged",
  "filter": {
    "op": "AND",
    "operands": [
      { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
      { "op": "BETWEEN", "field": "state.price", "lowerBound": 5, "upperBound": 50 }
    ]
  },
  "sort": [{ "field": "state.price", "direction": "ASC" }],
  "pagination": { "index": 1, "size": 20 }
}
```

该种类没有的属性会返回 400 `wow.query.malformed`，violation 代码为 `UNKNOWN_PROPERTY`；没有 `kind` 或 `kind` 为其他值的请求体，是 `INVALID_VALUE` 或 `UNKNOWN_VALUE`。

## 跨租户 {#across-tenants}

查询读取调用方的租户。带 `crossScope` 的调用方可以在查询路由上通过 `Wow-Scope` 指明其他租户来读取它们：`tenant=*` 表示每个租户，或者一个内部列表 `tenant=("t1" "t2")`。在 `wow.query.cross-tenant.enabled` 打开之前这项功能是关闭的，关闭时这样的查询返回 403 `wow.auth.forbidden`。

- 租户固定的聚合类型仍然只在那个租户中读取。不包含它的列表返回 400 `wow.query.malformed`。
- 无论是否有查询观察者做审计，每个跨租户查询都会在 `me.ahoo.wow.query.CrossTenantAudit` 上以 INFO 级别记录。
- 命令和状态读取从不跨租户：在那里，`tenant` 为 `*` 或列表会返回 400。
- 在进程内，`QueryApi.trusted()` 读取每个租户。

## 字段路径 {#field-paths}

字段由它的逻辑路径指明，这是一个由 `.` 连接各段的 JSON 字符串：`state.address.city`。每段以字母或 `_` 开头，包含字母、数字、`_` 和 `-`；第一段之后的段也可以是数组下标（`state.lines.0`）。

快照模型的记录包含系统字段，以及位于 `state` 下的状态：

| 字段 | 类型 | 包含 |
| --- | --- | --- |
| `tenantId` | string | 聚合的租户 |
| `aggregateId` | string | 聚合的 ID，在它的租户内唯一；记录的身份 |
| `version` | integer | 它的事件数 |
| `ownerId`、`spaceId` | string 或 `null` | 它的所有者和空间 |
| `deleted` | boolean | 它是否已删除 |
| `tags` | 由字符串数组组成的对象 | 它的标签；`tags.<key>` 指明其中一个 |
| `firstEventTime`、`lastEventTime` | integer | 它第一个和最后一个事件的时间，纪元毫秒 |
| `operator`、`firstOperator` | object 或 `null` | 谁发送了最后一条和第一条命令：`{id, tenantId, crossScope}`；匿名调用方为 `null`。按 `operator.id` 过滤 |
| `state.*` | 由状态 schema 决定 | 状态，按补全后的 Manifest 片段中的状态 schema 描述 |

模型不认识的路径返回 400 `wow.query.invalid`，`UNKNOWN_FIELD`。集合元素的字段只能在对该集合的 `ELEMENT_MATCH` 中、相对于元素来指明（否则为 `ELEMENT_SCOPE_REQUIRED`）。[能力描述](#the-capability-descriptor)列出每条路径，以及它接受的操作符、排序和聚合。

`events` 模型每条已提交的流一条记录：`aggregateId`、`tenantId`、`version`、`commitTime`、`commandId`、`requestId`、`commandType`、`ownerId`、`spaceId`、`tags`、`operator`，以及 `events`，每个事件为 `{name, revision, body}`。对事件的过滤是在 `events` 上的 `ELEMENT_MATCH`，对事件的聚合要先展开 `events`；两者见[查询事件流](../guide/event-queries)。部署可以要求每个事件查询都指明它的聚合（`wow.query.events.unpinned`）。

## 过滤 {#filters}

过滤是一棵由节点组成的树。每个节点是一个 JSON 对象，`op` 指明它的操作符，旁边是该操作符的属性。没有 `op` 的对象（9.x 的 `Condition`）会被拒绝。

### 值 {#values}

值是一个 JSON 标量：字符串、数字、布尔值，或者在操作符允许的地方为 `null`。数字保持它的精度：`1.50` 仍是小数，超过 2^53 的整数是精确的。值必须与状态 schema 中字段的类型相符（否则为 `VALUE_MISMATCH`）：字符串字段与字符串比较，数字字段与数字比较。时间字段（`Instant`、`LocalDate`，或带 `@QueryTemporal` 的整数或字符串）与它自己的编码比较：`Instant` 用纪元毫秒，`LocalDate` 用 `"2026-10-13"`。

### 比较 {#comparisons}

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `EQ` | `field`，`value`：标量或 `null` | 字段等于 `value`；`null` 表示询问字段是否为 null |
| `NE` | `field`、`value` | 字段不等于 `value` |
| `GT`、`GTE`、`LT`、`LTE` | `field`，`value`：标量，不能为 `null` | 字段大于、大于等于、小于、小于等于 |
| `BETWEEN` | `field`，`lowerBound`、`upperBound`：标量，不能为 `null` | `lowerBound <= field <= upperBound`，两端都包含 |
| `IN` | `field`，`values`：非空的标量数组，不含 `null` | 字段等于 `values` 中的某一个 |
| `NOT_IN` | `field`、`values` | 字段不等于 `values` 中的任何一个 |

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/in.json -->

```json
{
  "kind": "list",
  "filter": { "op": "IN", "field": "state.status", "values": ["PUBLISHED", "DELISTED"] },
  "limit": 50
}
```

`IN` 接收 `values`，而不是 `value`；`BETWEEN` 接收 `lowerBound` 和 `upperBound`，而不是一个数组。

### 字符串 {#strings}

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `CONTAINS` | `field`，`value`：字符串，`stringComparison` | 字段包含 `value` |
| `STARTS_WITH` | `field`、`value`、`stringComparison` | 字段以 `value` 开头 |
| `ENDS_WITH` | `field`、`value`、`stringComparison` | 字段以 `value` 结尾 |
| `IS_EMPTY_STRING` | `field` | 字段是 `""` |
| `IS_NOT_EMPTY_STRING` | `field` | 字段是 `""` 以外的字符串 |

`stringComparison` 是 `CASE_SENSITIVE`（默认）或 `CASE_INSENSITIVE`。

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/strings.json -->

```json
{
  "kind": "list",
  "filter": { "op": "CONTAINS", "field": "state.name", "value": "mug", "stringComparison": "CASE_INSENSITIVE" }
}
```

### 集合 {#collections}

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `CONTAINS_ALL` | `field`，`values`：非空的标量数组 | 数组字段包含 `values` 中的每一个 |
| `IS_EMPTY` | `field` | 数组字段为空 |
| `ELEMENT_MATCH` | `field`，`predicate`：一个过滤 | 数组字段中有一个元素匹配 `predicate`，其中的字段相对于元素 |

<!-- sample: fragment -->

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

谓词匹配一个元素：两个条件在同一行上成立。它不能包含系统字段过滤，也不能包含对整个模型的 `SEARCH`。

### 存在性 {#presence}

缺失的字段和显式的 `null` 都是 null；只有缺失的字段才是不存在。与某个值的比较、字符串操作符或范围，从不匹配 null 字段，而 `NE` 和 `NOT_IN` 会匹配。在数组字段上，标量操作数匹配任意元素，带数组操作数的 `EQ`（仅在进程内）匹配整个数组。

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `IS_NULL` | `field` | 字段为 `null` 或缺失 |
| `IS_NOT_NULL` | `field` | 字段有值 |
| `EXISTS` | `field` | 字段存在，包括 `null` |
| `NOT_EXISTS` | `field` | 字段缺失 |

没有品牌的商品：

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/presence.json -->

```json
{
  "kind": "list",
  "filter": { "op": "IS_NULL", "field": "state.brandId" }
}
```

### 相对时间 {#relative-time}

每个操作符都接收 `field`，以及可选的 `zoneId`（IANA 时区，例如 `Asia/Shanghai`，或者偏移量，例如 `+08:00`；默认 UTC，从不使用服务器自己的时区）、`datePattern`（`DateTimeFormatter` 模式，用于以格式化字符串存储的字段）和 `timeUnit`（`MILLISECONDS`，默认，或其他 `TimeUnit` 名称，用于以纪元数字存储的字段）。

| `op` | 其他属性 | 匹配 |
| --- | --- | --- |
| `TODAY`、`YESTERDAY`、`TOMORROW` | | 那一天 |
| `THIS_WEEK`、`LAST_WEEK`、`NEXT_WEEK` | | 那一周 |
| `THIS_MONTH`、`LAST_MONTH`、`NEXT_MONTH` | | 那个月 |
| `THIS_YEAR`、`LAST_YEAR`、`NEXT_YEAR` | | 那一年 |
| `BEFORE_TODAY` | `time`：`"18:00"` 或 `"18:00:30"` | 今天那个时间之前 |
| `RECENT_DAYS` | `days`：整数 | 最近 `days` 天之内，包括今天 |
| `EARLIER_DAYS` | `days` | 最近 `days` 天之前 |
| `BEFORE_NOW`、`AFTER_NOW` | `offset`：ISO-8601 时长，默认 `PT0S` | 服务器当前时间加上 `offset` 之前或之后（`-PT30M` 表示往回看半小时） |

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/relative-time.json -->

```json
{
  "kind": "list",
  "filter": { "op": "RECENT_DAYS", "field": "lastEventTime", "days": 7, "zoneId": "Asia/Shanghai" }
}
```

时刻以时刻的形式传递：纪元数字，或带偏移量的 ISO-8601 字符串（`2026-10-21T08:00:00Z`）。在一天的边界有意义的地方，请求要指明它的时区；调用方给出时区时，TypeScript DSL 总会发送 `zoneId`。

### 系统字段 {#system-fields}

这些操作符不指明 `field`：每个都过滤模型定义的一个系统字段。

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `ID` | `value`：字符串 | 记录的身份：在快照模型上是 `aggregateId` |
| `IDS` | `values`：非空的字符串数组 | 这些身份之一 |
| `AGGREGATE_ID`、`AGGREGATE_IDS` | `value` / `values` | 聚合 ID |
| `TENANT_ID`、`OWNER_ID`、`SPACE_ID` | `value` | 租户、所有者、空间 |
| `DELETION` | `state`：`ACTIVE`、`DELETED` 或 `ALL` | 处于该删除状态的聚合 |
| `MATCH_ALL`、`MATCH_NONE` | | 每条记录、没有记录 |

快照查询只看到没有删除的聚合，除非它的过滤自己指明了 `DELETION`。员工 `erin` 最后修改过的商品，无论是否已删除：

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/system-fields.json -->

```json
{
  "kind": "list",
  "filter": {
    "op": "AND",
    "operands": [
      { "op": "DELETION", "state": "ALL" },
      { "op": "EQ", "field": "operator.id", "value": "erin" }
    ]
  }
}
```

调用方的租户、所有者和空间总会被加上；过滤不能放宽它们。

### 检索与表达式 {#search-and-expressions}

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `SEARCH` | `query`：非空字符串，`fields`：路径（不写表示每个可检索的字段），`mode`：`TERMS`（默认）或 `PHRASE` | 全文匹配，需要后端支持（否则为 `MODEL_SEARCH_UNSUPPORTED`） |
| `EXPRESSION` | `expression`，`comparison`：`EQ`、`NE`、`GT`、`GTE`、`LT`、`LTE`，`value`：有限的数字 | 每条记录计算出的 `expression` 满足这个比较；没有值的记录不匹配 |

`expression` 是 `{"type":"FIELD","field":…}`、`{"type":"CONSTANT","value":…}`、`{"type":"BINARY","operator":"ADD|SUBTRACT|MULTIPLY|DIVIDE","left":…,"right":…}` 和 `{"type":"DATE_DIFF","from":…,"to":…,"unit":"SECOND|MINUTE|HOUR|DAY"}` 之一：

<!-- sample: fragment -->

```json
{
  "op": "EXPRESSION",
  "expression": { "type": "DATE_DIFF", "from": "state.paidAt", "to": "state.shippedAt", "unit": "HOUR" },
  "comparison": "GT",
  "value": 24
}
```

### 组合 {#combinators}

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `AND` | `operands`：非空的过滤数组 | 每个操作数 |
| `OR` | `operands` | 至少一个操作数 |
| `NOR` | `operands` | 没有任何操作数 |

没有 `NOT`：只有一个操作数的 `NOR` 就是对它取反。

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/nor.json -->

```json
{
  "kind": "list",
  "filter": { "op": "NOR", "operands": [{ "op": "EQ", "field": "state.status", "value": "DELISTED" }] }
}
```

### 上限与开销 {#limits-and-cost}

经 HTTP 时，一个过滤至多 `wow.query.budgets.http.max-filter-nodes` 个节点（128）和 `max-filter-values` 个值（1000）（`FILTER_TOO_LARGE`）。有些操作符无法使用索引：`NOR`、`NE`、`NOT_IN`、`CONTAINS`、`ENDS_WITH`、不区分大小写或为空的 `STARTS_WITH`、`IS_EMPTY`、`IS_NOT_EMPTY_STRING`、`IS_NULL`、`IS_NOT_NULL`、`NOT_EXISTS` 和 `EXPRESSION`。设置 `wow.query.budgets.http.allow-expensive-operators: false` 时，它们会被拒绝（`EXPENSIVE_OPERATOR_DISABLED`）。

## 排序与投影 {#sort-and-projection}

`sort` 是一个由 `{"field": …, "direction": "ASC" | "DESC"}` 组成的数组，至多 32 项，每个字段只出现一次。只有能力描述说明可以排序的字段（`sort.paged`、`sort.cursor`）才能排序。

`projection` 是 `{"include": [paths], "exclude": [paths]}`；两者都没有时，返回完整的记录。被包含的路径会保留它的子孙。

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/projection.json -->

```json
{
  "kind": "list",
  "filter": { "op": "MATCH_ALL" },
  "projection": { "include": ["aggregateId", "state.name", "state.price"] },
  "limit": 50
}
```

## 查询种类 {#query-kinds}

| `kind` | 请求属性 | 响应 |
| --- | --- | --- |
| `single` | `filter`、`projection`、`sort` | 按 `sort` 排序的第一条记录；没有匹配时返回 404 `wow.query.not-found` |
| `list` | `filter`、`projection`、`sort`、`limit` | 记录组成的 JSON 数组；带 `Accept: text/event-stream` 时为服务端推送事件 |
| `paged` | `filter`、`projection`、`sort`、`pagination`、`total` | `{"total": …, "exact": …, "list": [records]}` |
| `cursor` | `filter`、`projection`、`sort`、`size`、`cursor` | `{"list": [records], "nextCursor": …}` |
| `count` | `filter`、`limit` | `{"count": …, "exact": …}` |
| `aggregate` | `filter`、`elements`、`groupBy`、`metrics`、`sort`、`limit`、`having` | 行组成的 JSON 数组 |

除 `aggregate`（默认为 `MATCH_ALL`）之外，每个种类都必须有 `filter`；`{"op":"MATCH_ALL"}` 匹配每条记录。

### `list` {#list}

`limit` 是返回记录的最大数量。`0` 或不写时取 `wow.query.budgets.http.default-list-size`（100），至多允许 `max-list-size`（1000）（`SIZE_OUT_OF_RANGE`）。带 `Accept: text/event-stream` 时，每条记录是一个 `row` 事件（`id` 是从 0 开始的序号）；在第一条记录之后发生的失败，会以一个带 problem 的 `error` 事件结束流。

### `paged` {#paged}

`pagination` 默认为 `{"index": 1, "size": 10}`：`index` 从 1 开始计数，`size` 至多 `max-page-size`（100），`index × size` 至多 `max-page-window`（10000）。`total` 说明怎样计算总数：

- `BOUNDED`（默认）：至多数到 `wow.query.budgets.http.count-limit`（10000）。超出时 `exact` 为 false，`total` 是这个上限，读作“至少”（`10000+`）。
- `EXACT`：计算每一个匹配项；除非打开 `wow.query.budgets.http.allow-exact-count`，经 HTTP 时会被拒绝。
- `NONE`：不计数；`total` 为 `null`，`exact` 为 false。

<!-- sample: fragment -->

```json
{ "total": 10000, "exact": false, "list": [{ "aggregateId": "p-1", "...": "..." }] }
```

### `cursor` {#cursor}

游标分页返回 `cursor`（上一页的 `nextCursor`）之后的 `size` 条记录（默认 10，至多 `max-page-size`）；第一页没有游标。服务端会把记录的唯一键追加到 `sort` 中（在快照模型上是 `tenantId`、`aggregateId`），所以顺序是全序的，分页永远不会重复或跳过记录。最后一页的 `nextCursor` 为 `null`。游标是不透明的，属于它的模型和排序：用同样的排序和过滤发送它（换一种排序是 `INVALID_CURSOR`）。无限滚动使用游标；它从不计数。

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/cursor.json -->

```json
{
  "kind": "cursor",
  "filter": { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
  "sort": [{ "field": "lastEventTime", "direction": "DESC" }],
  "size": 20
}
```

<!-- sample: fragment -->

```json
{ "list": [{ "aggregateId": "o-9", "...": "..." }], "nextCursor": "eyJ…" }
```

### `count` {#count}

`limit` 至少为 1，它限制计数；不写时取 `count-limit`。计数在这个上限处停止时，`exact` 为 false。
<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/count.json -->

```json
{
  "kind": "count",
  "filter": { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
  "limit": 1000
}
```

计数用于一个经过过滤的、有上限的数字；固定的统计，例如每个状态的订单数，属于投影的计数器或数据集。

### `aggregate` {#aggregate}

聚合把匹配项分组，并为每组计算指标。`limit` 是分组的最大数量（默认 100）。

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/aggregate.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "NE", "field": "state.status", "value": "DRAFT" },
  "groupBy": [{ "type": "TERMS", "field": "state.status", "alias": "status" }],
  "metrics": [
    { "type": "COUNT", "alias": "count" },
    { "type": "NUMERIC", "function": "AVG", "expression": { "type": "FIELD", "field": "state.price" }, "alias": "averagePrice" }
  ],
  "sort": [{ "field": "count", "direction": "DESC" }]
}
```

<!-- sample: fragment -->

```json
[{ "status": "PUBLISHED", "count": 3, "averagePrice": 7.5 }, { "status": "DELISTED", "count": 1, "averagePrice": 4.0 }]
```

每一行包含分组别名和指标别名。

- **`elements`**：`[{"path": "state.lines", "filter": …}]` 展开一个集合，让分组和指标在它的元素上运行（至多 5 层）；此后字段相对于最内层的元素，带 `"scope": "ROOT"` 的分组则读取记录的绝对路径。
- **`groupBy`**：`TERMS`（`field` 或 `expression`，可选的 `missingKey`）、`HISTOGRAM`（`field` 或 `expression`，`interval`）、`DATE_HISTOGRAM`（`field`，`unit`：`YEAR`、`QUARTER`、`MONTH`、`WEEK`、`DAY`、`HOUR`、`MINUTE`、`SECOND`，`timeZone`，`dense`）和 `DATE_PART`（`field`，`part`：`DAY_OF_WEEK`、`DAY_OF_MONTH`、`HOUR_OF_DAY`、`MONTH_OF_YEAR`，`timeZone`，`dense`）；每个都有一个 `alias`。`dense` 填充空的桶，并要求这个分组是唯一的分组。
- **`metrics`**：`COUNT`、`NUMERIC`（`function`：`SUM`、`AVG`、`MIN`、`MAX`、`STDDEV`、`VARIANCE`，`expression`）、`DISTINCT_COUNT`（`expression`）、`PERCENTILE`（`expression`、`percentile`）、`ANY`、`FIRST` 和 `LAST`（`field`，`orderBy`，默认 `lastEventTime`），以及 `DERIVED`（基于其他指标的 `expression`：`METRIC_REF`、`CONSTANT`、`BINARY`、`TOTAL`、`RUNNING`、`PREVIOUS`、`RANK`）。除 `DERIVED` 之外，每个指标都接收一个可选的 `filter`，只统计匹配的部分。
- **`sort`** 指明分组别名和指标别名；没有指明的分组排在它们之后，按升序。
- **`having`** 保留指标匹配的分组：`CONDITION`（`metric`、`operator`、`value`）、`BETWEEN`（`metric`、`lower`、`upper`）、`IN`（`metric`、`values`）、`IS_NULL`（`metric`、`negated`）、`AND` 和 `OR`（`operands`）。

能力描述的 `analysis` 说明后端运行其中的哪些。

## 能力描述 {#the-capability-descriptor}

`GET /<context>/<aggregate>/<model>/query` 针对调用方的入口，返回模型接受什么：

<!-- sample: fragment -->

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

- `fields` 列出每条路径，以及它的值类型、它的 `filter` 接受的操作符、它能否在分页和游标中排序，以及它怎样分组和聚合。`elements` 是 `ELEMENT_MATCH` 或 `elements` 项能到达的集合，`dynamic` 是 map 键的模式，例如 `tags.{key}`。
- `record.rootOperators` 是不指明字段的操作符；组合操作符 `AND`、`OR` 和 `NOR` 在任何地方都适用。
- `version` 是能力描述的摘要。响应把它作为 `ETag` 携带；描述不变时，带 `If-None-Match: "<version>"` 的 `GET` 返回 304，所以客户端可以缓存它，并低成本地再次询问。

## 错误 {#errors}

| 错误码 | 状态 | 何时 |
| --- | --- | --- |
| `wow.query.malformed` | 400 | 请求体无法读成查询：`EMPTY_BODY`、`BODY_NOT_OBJECT`、`INVALID_JSON`、`UNKNOWN_PROPERTY`、`UNKNOWN_TYPE`（未知的 `op` 或 `type`）、`UNKNOWN_VALUE`、`INVALID_VALUE`、`INVALID_REQUEST`（违反了查询类型的规则，例如 `operands` 为空） |
| `wow.query.invalid` | 400 | 模型拒绝了它；每个 violation 指明它违反的规则，例如 `UNKNOWN_FIELD`、`UNSUPPORTED_CAPABILITY`、`VALUE_MISMATCH`、`ELEMENT_SCOPE_REQUIRED`、`SIZE_OUT_OF_RANGE`、`FILTER_TOO_LARGE`、`EXPENSIVE_OPERATOR_DISABLED`、`INVALID_CURSOR`、`STORAGE_UNSUPPORTED` |
| `wow.query.failed` | 500 | 后端失败 |
| `wow.query.timeout` | 504 | 查询运行的时间超过了 `wow.query.budgets.http.timeout`；不会重试 |

problem 的 `violations` 中每个 violation 都有一个 `path`（请求体中的 JSON 路径，例如 `filter.operands`）、一个 `code` 和一个 `message`。
