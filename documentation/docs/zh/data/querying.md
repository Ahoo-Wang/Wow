---
title: 分析查询
description: 用 Wow 查询语言回答分析人员的问题：计数、按时间和值分组、累计值和过滤后的分组，适用于快照、事件流和数据集，并在服务设定的上限之内运行。
---

# 分析查询

一次分析就是一个 `aggregate` 种类的查询：它过滤记录、把它们分组，并为每组计算指标。同一个请求体适用于每种模型：快照、事件流和数据集，发往该模型的查询路由。本页的示例按原样在 eShop 目录服务的测试中对它的商品运行。[查询语言](../reference/query-language)是完整的语法；本页是分析人员走过它的路径。

## 按值计数 {#count-by-a-value}

用 `TERMS` 对一个字段分组，并为每组计算指标：

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

回答是每组一行，包含分组别名和指标别名：`[{ "status": "PUBLISHED", "count": 3, "averagePrice": 7.5 }, …]`。`limit` 限制分组数量，默认 100。`count` 查询对记录计数而不分组：

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/count.json -->

```json
{
  "kind": "count",
  "filter": { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
  "limit": 1000
}
```

## 保留重要的分组 {#keep-the-groups-that-matter}

`having` 在指标算出之后，按指标过滤分组。至少有两个已发布商品的品牌，没有品牌的商品归为 `none`：

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/having.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
  "groupBy": [{ "type": "TERMS", "field": "state.brandId", "missingKey": "none", "alias": "brand" }],
  "metrics": [{ "type": "COUNT", "alias": "products" }],
  "having": { "type": "CONDITION", "metric": "products", "operator": "GTE", "value": 2 },
  "sort": [{ "field": "products", "direction": "DESC" }],
  "limit": 10
}
```

## 按时间和区间分组 {#group-by-time-and-by-range}

`DATE_HISTOGRAM` 在某个时区中按日历单位对时间字段分组；`HISTOGRAM` 按间隔对数字分组。过去一年中每月上架的商品，以及累计值：

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/monthly.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "RECENT_DAYS", "field": "firstEventTime", "days": 365 },
  "groupBy": [{ "type": "DATE_HISTOGRAM", "field": "firstEventTime", "unit": "MONTH", "timeZone": "UTC", "alias": "month" }],
  "metrics": [
    { "type": "COUNT", "alias": "listed" },
    { "type": "DERIVED", "expression": { "type": "RUNNING", "metric": "listed", "order": { "field": "month", "direction": "ASC" } }, "alias": "listedSoFar" }
  ],
  "sort": [{ "field": "month", "direction": "ASC" }]
}
```

`RUNNING` 沿着分组的某种顺序累计一个指标。其他作用于分组的窗口有 `TOTAL`（占整体的比例）、`PREVIOUS`（前一个桶或前一个周期的同一指标，用于与上月或去年相比的增长）和 `RANK`；`DERIVED` 指标还可以用算术组合多个指标，例如转化率。按价格每 10 一档统计已发布的商品，以及每档中最便宜的那个：

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/price-bands.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
  "groupBy": [{ "type": "HISTOGRAM", "field": "state.price", "interval": 10, "alias": "band" }],
  "metrics": [
    { "type": "COUNT", "alias": "products" },
    { "type": "NUMERIC", "function": "MIN", "expression": { "type": "FIELD", "field": "state.price" }, "alias": "cheapest" }
  ],
  "sort": [{ "field": "band", "direction": "ASC" }]
}
```

`DATE_PART` 则按日期的某个部分分组，即星期几或一天中的小时，用来看事情在什么时候发生。当日期分组是唯一的分组时，`"dense": true` 会填充其中的空桶，让图表没有空缺。

## 相对于现在的时间 {#time-relative-to-now}

时间上的过滤相对于服务的时钟，每次查询读取一次，从不使用浏览器的时钟，所以保存下来的查询明天的含义也相同：

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/relative-time.json -->

```json
{
  "kind": "list",
  "filter": { "op": "RECENT_DAYS", "field": "lastEventTime", "days": 7, "zoneId": "Asia/Shanghai" }
}
```

日历过滤（`TODAY`、`THIS_WEEK`、`LAST_MONTH`、`RECENT_DAYS` 等）在过滤指明的 `zoneId` 中读取日期，没有指明时用 UTC。`BEFORE_NOW` 和 `AFTER_NOW` 与现在加上一个 ISO-8601 偏移量比较。[相对时间](../reference/query-language#relative-time)列出了全部过滤。

## 发生了什么，而不是现在是什么 {#what-happened-not-what-is}

对快照的分析统计的是事物现在的样子。要统计发生了什么，就查询聚合的事件流：每次提交一条记录，它的事件在 `events` 中。对事件的条件是 `events` 上的 `ELEMENT_MATCH`，对事件的聚合用 `elements` 展开 `events`，让它的分组和指标在每个事件上运行（[查询事件流](../guide/event-queries)）。

## 跨聚合与跨服务 {#across-aggregates-and-services}

快照或事件查询读取一个服务的一种聚合类型。把订单与其客户关联起来的分析，或者跨服务的销售分析，读取的是数据集：在数仓之上定义的表，用同样的请求体在 `POST /wow/dataset/<context>/<name>/query` 上查询（[数据集与分析目录](./datasets)）。

## 上限与开销 {#limits-and-cost}

每个查询都在提供它的服务的预算之内运行：

- 通过 HTTP，一个过滤至多 128 个节点、1,000 个值；除非 `limit` 另行指定，聚合回答 100 个分组。
- 有些操作符无法使用索引，例如 `NE`、`NOT_IN`、`CONTAINS` 和 `EXPRESSION`。服务可以拒绝它们（`wow.query.http.allow-expensive-operators: false`）。
- 运行时间超出预算的查询以 504 `wow.query.timeout` 回答，超出存储并发查询数的查询以 429 `wow.query.busy` 回答，并带有 `Retry-After`。数据集查询有自己的舱壁（`wow.query.dataset.max-concurrent`，8），所以分析永远不会挤占服务自己的读取。

能力描述符，即对同一路由的 `GET`，说明每个字段接受什么以及适用的上限（[能力描述](../reference/query-language#the-capability-descriptor)）：在构建模型可能拒绝的查询之前先读它。
