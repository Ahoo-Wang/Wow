---
title: 查询语言
description: 快照、事件和数据集模型的 JSON 查询语言：字段、过滤、排序和投影，查询种类及其回答，能力描述，以及错误。
outline: deep
---

# 查询语言

服务的每个查询模型，即每个聚合类型的快照和事件，以及每个数据集，都在 `POST …/query` 上接收同样的 JSON 查询。本页是这门语言的参考。示例查询 eShop catalog 的商品，每个示例都在 catalog 服务的测试中运行过。怎样在 Kotlin 中和经 HTTP 使用它，见[查询快照](../guide/queries)。

## 请求 {#a-request}

请求体是一个 JSON 对象：`kind` 指明操作，其他属性是该种类的查询。

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/request.json -->

```json
{
  "kind": "paged",
  "filter": {
    "op": "and",
    "operands": [
      { "op": "eq", "field": "state.status", "value": "PUBLISHED" },
      { "op": "between", "field": "state.price", "lowerBound": 5, "upperBound": 50 }
    ]
  },
  "sort": [{ "field": "state.price", "direction": "asc" }],
  "pagination": { "index": 1, "size": 20 }
}
```

该种类没有的属性会返回 400 `wow.query.malformed`，violation 代码为 `unknown-property`；没有 `kind` 或 `kind` 为其他值的请求体，是 `invalid-value` 或 `unknown-value`。

协议自己定义的词一律是小写短横线：`kind`、`op`、`type`、`direction`、`total`、`mode`、`unit` 等枚举类属性，错误码与 violation 代码，以及描述符里的取值（`"not-in"`、`"date-histogram"`、`"unknown-field"`、`"snapshot"`）。只认这一种写法：`"NOT_IN"` 是 `unknown-type`。后端注册的自定义查询模型或值类型遵守同一规则，不符合的会在服务启动时被拒绝，消息里给出小写的写法。有两类取值保留自己的写法，因为它们不是协议定义的：领域的取值（下文的 `"PUBLISHED"` 是商品的状态），以及时区 ID 与 ISO-8601 时长（`Asia/Shanghai`、`PT30M`）。描述符里的时间单位是 JDK `TimeUnit` 名称的小写（`"milliseconds"`）。

## 跨租户 {#across-tenants}

查询读取调用方的租户。带 `crossScope` 的调用方可以在查询路由上通过 `Wow-Scope` 指明其他租户来读取它们：`tenant=*` 表示每个租户，或者一个内部列表 `tenant=("t1" "t2")`。在 `wow.query.allow-cross-tenant` 打开之前这项功能是关闭的，关闭时这样的查询返回 403 `wow.auth.forbidden`。

- 租户固定的聚合类型仍然只在那个租户中读取。不包含它的列表返回 400 `wow.query.malformed`。
- 无论是否有查询观察者做审计，每个跨租户查询都会在 `me.ahoo.wow.query.CrossTenantAudit` 上以 INFO 级别记录。
- 命令和状态读取从不跨租户：在那里，`tenant` 为 `*` 或列表会返回 400。
- 在进程内，受信代码通过 `NodeQueryApi.trusted()` 读取每个租户，这是它所在节点的读取 API：作业、投影的重建、服务自己的路由。客户端的 `QueryApi`，即 `WowClient` 为另一个服务给出的那个，只有 `of(caller)`，因为对端总是代调用方读取，从不受信。受信的快照查询按 ref 读取状态（`state(ref)`），因为没有租户限定它们；调用方的查询还可以按聚合 ID 读取。

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
| `access` | object | 它声明的访问属性；`access.<name>` 指明其中一个，类型按 Manifest 的声明 |
| `firstEventTime`、`lastEventTime` | integer | 它第一个和最后一个事件的时间，纪元毫秒 |
| `operator`、`firstOperator` | object 或 `null` | 谁发送了最后一条和第一条命令：`{id, tenantId, actedElsewhere}`；匿名调用方为 `null`。按 `operator.id` 过滤 |
| `schedules` | object | 它保有的调度，按键存放每条规则：`type`（`at`、`after` 或 `cron`），以及 `epochMillis`、`millis`，或 `expression`、`zone` 和 `misfire`；`schedules.<key>` 指明其中一条 |
| `state.*` | 由状态 schema 决定 | 状态，按补全后的 Manifest 片段中的状态 schema 描述 |

模型不认识的路径返回 400 `wow.query.invalid`，`unknown-field`。集合元素的字段只能在对该集合的 `element-match` 中、相对于元素来指明（否则为 `element-scope-required`）。[能力描述](#the-capability-descriptor)列出每条路径，以及它接受的操作符、排序和聚合。字段的 `aliases`（能力描述列出它们）是查询可以用来指明它的其他路径，例如它改名之前的名字；它们只用于输入：响应总是把每个字段放在它的规范 `path` 下。

`events` 模型每条已提交的流一条记录：`aggregateId`、`tenantId`、`version`、`commitTime`、`commandId`、`requestId`、`commandType`、`ownerId`、`spaceId`、`access`（聚合当前的访问属性，`owner` 与 `space` 也在其中，`access.<name>` 指明其中一个；未盖章的流为 `null`）、`operator`，以及 `events`，每个事件为 `{name, revision, body}`。对事件的过滤是在 `events` 上的 `element-match`，对事件的聚合要先展开 `events`；两者见[查询事件流](../guide/event-queries)。部署可以要求每个事件查询都指明它的聚合（`wow.query.events.unpinned`）。

## 过滤 {#filters}

过滤是一棵由节点组成的树。每个节点是一个 JSON 对象，`op` 指明它的操作符，旁边是该操作符的属性。没有 `op` 的对象（9.x 的 `Condition`）会被拒绝。

### 值 {#values}

值是一个 JSON 标量：字符串、数字、布尔值，或者在操作符允许的地方为 `null`。数字保持它的精度：`1.50` 仍是小数，超过 2^53 的整数是精确的。值必须与状态 schema 中字段的类型相符（否则为 `value-mismatch`）：字符串字段与字符串比较，数字字段与数字比较。时间字段（`Instant`、`LocalDate`，或带 `@QueryTemporal` 的整数或字符串）与它自己的编码比较：`Instant` 用纪元毫秒，`LocalDate` 用 `"2026-10-13"`。

### 比较 {#comparisons}

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `eq` | `field`，`value`：标量或 `null` | 字段等于 `value`；`null` 表示询问字段是否为 null |
| `ne` | `field`、`value` | 字段不等于 `value` |
| `gt`、`gte`、`lt`、`lte` | `field`，`value`：标量，不能为 `null` | 字段大于、大于等于、小于、小于等于 |
| `between` | `field`，`lowerBound`、`upperBound`：标量，不能为 `null` | `lowerBound <= field <= upperBound`，两端都包含 |
| `in` | `field`，`values`：非空的标量数组，不含 `null` | 字段等于 `values` 中的某一个 |
| `not-in` | `field`、`values` | 字段不等于 `values` 中的任何一个 |

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/in.json -->

```json
{
  "kind": "list",
  "filter": { "op": "in", "field": "state.status", "values": ["PUBLISHED", "DELISTED"] },
  "limit": 50
}
```

`in` 接收 `values`，而不是 `value`；`between` 接收 `lowerBound` 和 `upperBound`，而不是一个数组。

### 字符串 {#strings}

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `contains` | `field`，`value`：字符串，`stringComparison` | 字段包含 `value` |
| `starts-with` | `field`、`value`、`stringComparison` | 字段以 `value` 开头 |
| `ends-with` | `field`、`value`、`stringComparison` | 字段以 `value` 结尾 |
| `is-empty-string` | `field` | 字段是 `""` |
| `is-not-empty-string` | `field` | 字段是 `""` 以外的字符串 |

`stringComparison` 是 `case-sensitive`（默认）或 `case-insensitive`。

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/strings.json -->

```json
{
  "kind": "list",
  "filter": { "op": "contains", "field": "state.name", "value": "mug", "stringComparison": "case-insensitive" }
}
```

### 集合 {#collections}

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `contains-all` | `field`，`values`：非空的标量数组 | 数组字段包含 `values` 中的每一个 |
| `is-empty` | `field` | 数组字段为空 |
| `element-match` | `field`，`predicate`：一个过滤 | 数组字段中有一个元素匹配 `predicate`，其中的字段相对于元素 |

<!-- sample: fragment -->

```json
{
  "op": "element-match",
  "field": "state.lines",
  "predicate": {
    "op": "and",
    "operands": [
      { "op": "eq", "field": "sku", "value": "B" },
      { "op": "gte", "field": "quantity", "value": 2 }
    ]
  }
}
```

谓词匹配一个元素：两个条件在同一行上成立。它不能包含系统字段过滤、对整个模型的 `search`，也不能包含 `expression`（`400 wow.query.malformed`，`invalid-request`）。

### 存在性 {#presence}

缺失的字段和显式的 `null` 都是 null；只有缺失的字段才是不存在。与某个值的比较、字符串操作符或范围，从不匹配 null 字段，而 `ne` 和 `not-in` 会匹配。在数组字段上，标量操作数匹配任意元素，带数组操作数的 `eq`（仅在进程内）匹配整个数组。

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `is-null` | `field` | 字段为 `null` 或缺失 |
| `is-not-null` | `field` | 字段有值 |
| `exists` | `field` | 字段存在，包括 `null` |
| `not-exists` | `field` | 字段缺失 |

没有品牌的商品：

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/presence.json -->

```json
{
  "kind": "list",
  "filter": { "op": "is-null", "field": "state.brandId" }
}
```

### 相对时间 {#relative-time}

每个操作符都接收 `field`，以及可选的 `timeZone`（IANA 时区，例如 `Asia/Shanghai`，或者偏移量，例如 `+08:00`；默认 UTC，从不使用服务器自己的时区）。过滤器不带编码：服务器按字段记时间的方式写出窗口的边界，即模型声明的方式（`@QueryTemporal`），纪元数字用它的单位，文本用它的模式。

| `op` | 其他属性 | 匹配 |
| --- | --- | --- |
| `today`、`yesterday`、`tomorrow` | | 那一天 |
| `this-week`、`last-week`、`next-week` | | 那一周 |
| `this-month`、`last-month`、`next-month` | | 那个月 |
| `this-year`、`last-year`、`next-year` | | 那一年 |
| `before-today` | `time`：`"18:00"` 或 `"18:00:30"` | 今天那个时间之前 |
| `recent-days` | `days`：整数 | 最近 `days` 天之内，包括今天 |
| `earlier-days` | `days` | 最近 `days` 天之前 |
| `before-now`、`after-now` | `offset`：ISO-8601 时长，默认 `PT0S` | 服务器当前时间加上 `offset` 之前或之后（`-PT30M` 表示往回看半小时） |

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/relative-time.json -->

```json
{
  "kind": "list",
  "filter": { "op": "recent-days", "field": "lastEventTime", "days": 7, "timeZone": "Asia/Shanghai" }
}
```

时刻以时刻的形式传递：纪元数字，或带偏移量的 ISO-8601 字符串（`2026-10-21T08:00:00Z`）。在一天的边界有意义的地方，请求要指明它的时区；调用方给出时区时，TypeScript DSL 总会发送 `timeZone`。

### 系统字段 {#system-fields}

这些操作符不指明 `field`：每个都过滤模型定义的一个系统字段。

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `id` | `value`：字符串 | 记录的身份：在快照模型上是 `aggregateId` |
| `ids` | `values`：非空的字符串数组 | 这些身份之一 |
| `aggregate-id`、`aggregate-ids` | `value` / `values` | 聚合 ID |
| `tenant-id`、`owner-id`、`space-id` | `value` | 租户、所有者、空间 |
| `deletion` | `state`：`active`、`deleted` 或 `all` | 处于该删除状态的聚合 |
| `match-all`、`match-none` | | 每条记录、没有记录 |

快照查询只看到没有删除的聚合，除非它的过滤自己指明了 `deletion`。员工 `erin` 最后修改过的商品，无论是否已删除：

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/system-fields.json -->

```json
{
  "kind": "list",
  "filter": {
    "op": "and",
    "operands": [
      { "op": "deletion", "state": "all" },
      { "op": "eq", "field": "operator.id", "value": "erin" }
    ]
  }
}
```

调用方的租户、所有者和空间总会被加上；过滤不能放宽它们。

### 检索与表达式 {#search-and-expressions}

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `search` | `query`：非空字符串，`fields`：路径（不写表示后端检索整条记录时读的字段：Elasticsearch 上为标了 `@QueryText` 的字段，MongoDB 上为它的文本索引中的字段），`mode`：`terms`（默认）或 `phrase` | 全文匹配，需要后端支持：不指定字段而后端不提供时为 `model-search-unsupported`，字段不能按词检索时为 `unsupported-capability` |
| `expression` | `expression`，`comparison`：`eq`、`ne`、`gt`、`gte`、`lt`、`lte`，`value`：有限的数字 | 每条记录计算出的 `expression` 满足这个比较；没有值的记录不匹配 |

`expression` 是 `{"type":"field","field":…}`、`{"type":"constant","value":…}`、`{"type":"binary","operator":"add|subtract|multiply|divide","left":…,"right":…}` 和 `{"type":"date-diff","from":…,"to":…,"unit":"second|minute|hour|day"}` 之一：

<!-- sample: fragment -->

```json
{
  "op": "expression",
  "expression": { "type": "date-diff", "from": "state.paidAt", "to": "state.shippedAt", "unit": "hour" },
  "comparison": "gt",
  "value": 24
}
```

### 组合 {#combinators}

| `op` | 属性 | 匹配 |
| --- | --- | --- |
| `and` | `operands`：非空的过滤数组 | 每个操作数 |
| `or` | `operands` | 至少一个操作数 |
| `nor` | `operands` | 没有任何操作数 |

没有 `not`：只有一个操作数的 `nor` 就是对它取反。

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/nor.json -->

```json
{
  "kind": "list",
  "filter": { "op": "nor", "operands": [{ "op": "eq", "field": "state.status", "value": "DELISTED" }] }
}
```

### 上限与开销 {#limits-and-cost}

经 HTTP 时，一个过滤至多 `wow.query.budgets.http.max-filter-nodes` 个节点（128，调用方的范围也计入），每个节点至多列出 `max-filter-values` 个值（1000），例如 `in` 的值（`filter-too-large`）。有些操作符无法使用索引：`nor`、`ne`、`not-in`、`contains`、`ends-with`、不区分大小写或为空的 `starts-with`、`is-empty`、`is-not-empty-string`、`is-null`、`is-not-null`、`not-exists` 和 `expression`。设置 `wow.query.budgets.http.allow-expensive-operators: false` 时，它们会被拒绝（`expensive-operator-disabled`）。

## 排序与投影 {#sort-and-projection}

`sort` 是一个由 `{"field": …, "direction": "asc" | "desc"}` 组成的数组，至多 32 项，每个字段只出现一次。只有能力描述说明可以排序的字段（`sort.paged`、`sort.cursor`）才能排序。缺失值与 `null` 升序排在最前、降序排在最后；数组升序按其最小元素排序（`null` 元素最小），降序按其最大元素排序。无法这样排序元素可能为 `null` 的数组的存储（Elasticsearch）不提供该字段的排序。

在所有排序字段上都相同的记录，按记录的唯一键（快照模型上是 `tenantId`、`aggregateId`；数据集是它的标识）排序，方向与最后一个排序字段相同；没有排序时为升序。所以按 `lastEventTime` 降序排序的列表、分页和游标，会把并列的记录按键降序给出，各页之间的顺序也一致。

`projection` 是 `{"include": [paths], "exclude": [paths]}`；两者都没有时，返回完整的记录。被包含的路径会保留它的子孙。

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/projection.json -->

```json
{
  "kind": "list",
  "filter": { "op": "match-all" },
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

除 `aggregate`（默认为 `match-all`）之外，每个种类都必须有 `filter`；`{"op":"match-all"}` 匹配每条记录。

### `list` {#list}

`limit` 是返回记录的最大数量，至少为 1。不写时取 `wow.query.budgets.http.default-list-size`（100），至多允许 `max-list-size`（1000）（`size-out-of-range`）。`limit` 小于 1、`pagination` 的页码或页大小小于 1，或者 `sort` 超过 32 个字段，会在读取请求体时被拒绝（`400 wow.query.malformed`，`invalid-request`）。带 `Accept: text/event-stream` 时，记录以服务端推送事件的形式流出，每条一个 `row` 事件，流总是以一个 `end` 事件结束，`{"reason": …, "resumable": …}`：

- **可续读。** 存储按 keyset 分页（描述符的 `record.stream.resumable`）且排序能构成游标时，服务端按每块 `wow.query.stream.chunk-size` 条（500）分块读取匹配的记录：请求写了 `limit` 时至多读取这么多条，不写时读取全部匹配；不受预算（`default-list-size`、`max-list-size`）限制，每个 `row` 以它的位置作为 `id`。带 `Last-Event-ID` 的请求从那条记录之后严格地继续，在任何节点上都一样。流运行 `wow.query.stream.max-hold`（30 秒）后以 `hold-expired` 结束，存储在下一块之前没有空闲许可时以 `permit-reclaimed` 结束，节点停止时以 `node-stopping` 结束；这几种都可以续读，客户端从最后一个 `id` 再请求即可。所有匹配都发送完时以 `complete` 结束。
- **不可续读。** 其他列表按普通列表的方式流出，受它的上限约束，行不带 `id`，每个 `end` 都是 `"resumable": false`。这样的列表必须写明 `limit`：不写会被拒绝，`wow.query.invalid`，代码 `stream-not-resumable`，而不是悄悄停在默认条数。

在第一条记录之后发生的失败，会以一个带 problem 的 `error` 事件结束流。在 Kotlin 里，`stream(query, after)` 遵守同样的规则，每行带它的位置，对不写 `limit` 的不可续读列表同样拒绝。

### `paged` {#paged}

`pagination` 默认为 `{"index": 1, "size": 10}`：`index` 从 1 开始计数，`size` 至多 `max-page-size`（100），`index × size` 至多 `max-page-window`（10000）。`total` 说明怎样计算总数：

- `bounded`（默认）：至多数到 `wow.query.budgets.http.count-limit`（10000）。超出时 `exact` 为 false，`total` 是这个上限，读作“至少”（`10000+`）。
- `exact`：计算每一个匹配项；除非打开 `wow.query.budgets.http.allow-exact-count`，经 HTTP 时会被拒绝。
- `none`：不计数；`total` 为 `null`，`exact` 为 false。

<!-- sample: fragment -->

```json
{ "total": 10000, "exact": false, "list": [{ "aggregateId": "p-1", "...": "..." }] }
```

### `cursor` {#cursor}

游标分页返回 `cursor`（上一页的 `nextCursor`）之后的 `size` 条记录（默认 10，至多 `max-page-size`）；第一页没有游标。服务端会把记录的唯一键追加到 `sort` 中（在快照模型上是 `tenantId`、`aggregateId`），方向与最后一个排序字段相同，所以顺序是全序的，分页永远不会重复或跳过记录。最后一页的 `nextCursor` 为 `null`。游标是不透明的，属于它的模型和排序：用同样的排序和过滤发送它（换一种排序是 `invalid-cursor`）。无限滚动使用游标；它从不计数。

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/cursor.json -->

```json
{
  "kind": "cursor",
  "filter": { "op": "eq", "field": "state.status", "value": "PUBLISHED" },
  "sort": [{ "field": "lastEventTime", "direction": "desc" }],
  "size": 20
}
```

<!-- sample: fragment -->

```json
{ "list": [{ "aggregateId": "o-9", "...": "..." }], "nextCursor": "eyJ…" }
```

### `count` {#count}

`limit` 至少为 1，它限制计数；不写时取 `count-limit`。计数在这个上限处停止时，`exact` 为 false。
<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/count.json -->

```json
{
  "kind": "count",
  "filter": { "op": "eq", "field": "state.status", "value": "PUBLISHED" },
  "limit": 1000
}
```

计数用于一个经过过滤的、有上限的数字；固定的统计，例如每个状态的订单数，属于投影的计数器或数据集。

### `aggregate` {#aggregate}

聚合把匹配项分组，并为每组计算指标。`limit` 是分组的最大数量（默认 100）。带 `sort` 时，返回按该顺序排在前面的 `limit` 个分组，即前 N 名。不带 `sort` 时，分组多于 `limit` 会被拒绝，400 `wow.query.too-many-groups`（violation `too-many-groups`，位于 `limit`），而不是按分组键截断：需要前 N 名就加上排序，否则调大 `limit`。

<!-- sample: examples/eshop/catalog/catalog-server/src/test/resources/docs/query-language/aggregate.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "ne", "field": "state.status", "value": "DRAFT" },
  "groupBy": [{ "type": "terms", "field": "state.status", "alias": "status" }],
  "metrics": [
    { "type": "count", "alias": "count" },
    { "type": "numeric", "function": "avg", "expression": { "type": "field", "field": "state.price" }, "alias": "averagePrice" }
  ],
  "sort": [{ "field": "count", "direction": "desc" }]
}
```

<!-- sample: fragment -->

```json
[{ "status": "PUBLISHED", "count": 3, "averagePrice": 7.5 }, { "status": "DELISTED", "count": 1, "averagePrice": 4.0 }]
```

每一行包含分组别名和指标别名。

- **`elements`**：`[{"path": "state.lines", "filter": …}]` 展开一个集合，让分组和指标在它的元素上运行（至多 5 层）；此后字段相对于最内层的元素，带 `"scope": "root"` 的分组则读取记录的绝对路径。
- **`groupBy`**：`terms`（`field` 或 `expression`，可选的 `missingKey`）、`histogram`（`field` 或 `expression`，`interval`）、`date-histogram`（`field`，`unit`：`year`、`quarter`、`month`、`week`、`day`、`hour`、`minute`、`second`，`timeZone`，`dense`）和 `date-part`（`field`，`part`：`day-of-week`、`day-of-month`、`hour-of-day`、`month-of-year`，`timeZone`，`dense`）；每个都有一个 `alias`。`dense` 填充空的桶，并要求这个分组是唯一的分组。`date-histogram` 的键是记录所在的本地 `unit` 周期在 `timeZone` 中开始的时刻（毫秒时间戳），按该周期自己的偏移：夏令时回拨重复的那个小时是两个桶，春季跳过的那个小时没有桶；沿它的 `previous` 一步走到前一个桶（带 `period` 时走到上一个周期里同一本地时间所在的桶）。有一个限制：在时区偏移的变化量不是单位整数倍的那一天（历史上以分、秒计的变化，如 Africa/Monrovia 在 1972 年的变化），各存储按自己的截断放置那天的 HOUR 与 MINUTE 的桶，可能与这条规则不同。在 ClickHouse 上，1970 年以前的每个这样的日子也是如此，多是时区从地方平太阳时的切换（如 Asia/Shanghai 在 1901-01-01 的切换）。
- **`metrics`**：`count`、`numeric`（`function`：`sum`、`avg`、`min`、`max`、`stddev`、`variance`，`expression`）、`distinct-count`（`expression`）、`percentile`（`expression`、`percentile`）、`any`（`field`）、`first` 和 `last`（`field`，`orderBy`：默认为模型的事件时间，快照上为 `lastEventTime`，事件上为 `commitTime`；在 `elements` 内必须写明，否则为 `first-last-requires-order-by`），以及 `derived`（基于其他指标的 `expression`：`metric-ref`、`constant`、`binary`、`total`、`running`、`previous`、`rank`）。除 `derived` 之外，每个指标都接收一个可选的 `filter`，只统计匹配的部分。
- **`sort`** 指明分组别名和指标别名；没有指明的分组排在它们之后，按升序。
- **`having`** 保留指标匹配的分组：`condition`（`metric`、`operator`、`value`）、`between`（`metric`、`lower`、`upper`）、`in`（`metric`、`values`）、`is-null`（`metric`、`negated`）、`and` 和 `or`（`operands`）。

能力描述的 `analysis` 说明后端运行其中的哪些。

## 能力描述 {#the-capability-descriptor}

`GET /<context>/<aggregate>/<model>/query` 针对调用方的入口，返回模型接受什么：

<!-- sample: fragment -->

```json
{
  "model": "snapshot",
  "version": "sha256:b3c7…",
  "timeZone": "Z",
  "record": { "identity": "aggregateId", "paging": ["list", "paged", "cursor"], "defaultScope": "active",
              "rootOperators": ["id", "ids", "aggregate-id", "aggregate-ids", "tenant-id", "owner-id", "space-id", "deletion", "expression"],
              "stream": { "resumable": true } },
  "limits": { "maxListSize": 1000, "defaultListSize": 100, "maxPageSize": 100, "maxPageWindow": 10000, "maxFilterNodes": 128, "...": "..." },
  "analysis": { "metrics": ["count", "numeric", "..."], "...": "..." },
  "fields": [
    { "path": "aggregateId", "types": ["string"], "kind": "scalar", "nullable": false, "project": true,
      "filter": { "operators": ["eq", "ne", "gt", "..."] }, "sort": { "paged": true, "cursor": true },
      "aggregate": { "groups": ["terms"], "...": "..." }, "aliases": [] }
  ],
  "elements": [{ "path": "state.lines", "filter": true, "aggregate": true }],
  "dynamic": [
    { "pattern": "state.deliveryDates.{key}", "types": ["string"], "kind": "scalar", "nullable": true, "semantic": { "type": "temporal-date" },
      "filter": { "operators": ["eq", "gt", "..."] }, "sort": { "paged": true, "cursor": false }, "aggregate": { "groups": ["terms", "date-histogram"], "...": "..." } }
  ],
  "constraints": [{ "type": "cursor-unique-sort", "appended": "aggregateId", "fields": ["tenantId", "aggregateId"] }]
}
```

- `fields` 列出每条路径，以及它的值类型、能否为 `null`、它的 `semantic` 语义类型（若有：日期、纪元时间、金额、时长、引用）、它的 `filter` 接受的操作符、它能否在分页和游标中排序，以及它怎样分组和聚合。`elements` 是 `element-match` 或 `elements` 项能到达的集合。
- `dynamic` 列出 map 键的模式，例如状态里的 `Map<String, LocalDate>`。每一项都说明字段项对任意一个键会说明的内容：`nullable`、`semantic`、`filter`、`sort` 和 `aggregate`，所以客户端可以像对待声明过的字段一样展示和查询 `state.deliveryDates.berlin`。map 属性上的语义注解描述的是它的值：`Map<String, Long>` 上的 `@QueryTemporal` 让每个值都是纪元时间。另外单独声明过的键列在 `fields` 里，并写在该模式的 `excludedKeys` 中。
- 数组字段上的 `terms` 分组，把一条记录分到它的每个不同元素下，所以 `["a", "b", "a"]` 在 `a` 与 `b` 里各算一次；没有元素的记录（数组为空或不存在）进入 `null` 组。每个能按数组分组的后端都这样做；不能的后端会在该字段的 `aggregate.groups` 里不列出 `terms`，请求它的查询以 `unsupported-capability` 被拒绝。
- `record.rootOperators` 是不指明字段的操作符；组合操作符 `and`、`or` 和 `nor` 在任何地方都适用。
- `version` 是能力描述的摘要。响应把它作为 `ETag` 携带；描述不变时，带 `If-None-Match: "<version>"` 的 `GET` 返回 304，所以客户端可以缓存它，并低成本地再次询问。

## 错误 {#errors}

| 错误码 | 状态 | 何时 |
| --- | --- | --- |
| `wow.query.malformed` | 400 | 请求体无法读成查询：`empty-body`、`body-not-object`、`invalid-json`、`unknown-property`、`unknown-type`（未知的 `op` 或 `type`）、`unknown-value`、`invalid-value`、`invalid-request`（违反了查询类型的规则，例如 `operands` 为空） |
| `wow.query.invalid` | 400 | 模型拒绝了它；每个 violation 指明它违反的规则，例如 `unknown-field`、`unsupported-capability`、`value-mismatch`、`element-scope-required`、`size-out-of-range`、`filter-too-large`、`expensive-operator-disabled`、`invalid-cursor`、`stream-not-resumable`、`storage-unsupported` |
| `wow.query.failed` | 500 | 后端失败 |
| `wow.query.too-many-groups` | 400 | 不带 `sort` 的 `aggregate` 得到的分组多于 `limit` |
| `wow.query.busy` | 429 | 存储运行的查询已达其上限；查询未运行（`Retry-After`） |
| `wow.storage.unavailable` | 503 | 存储无法连接或未及时应答 |
| `wow.query.timeout` | 504 | 查询运行的时间超过了 `wow.query.budgets.http.timeout`；不会重试 |

problem 的 `violations` 中每个 violation 都有一个 `path`（请求体中的 JSON 路径，例如 `filter.operands`）、一个 `code` 和一个 `message`。
