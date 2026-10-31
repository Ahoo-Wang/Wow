---
title: 从服务发布数据集
description: 在一个文件中基于服务的数仓表声明数据集，把它的行限定给调用方，为字段脱敏，并像快照模型一样查询它。
outline: deep
---

# 从服务发布数据集

数据集是一张用于分析的只读表，基于服务的数仓表声明，像快照模型一样查询：过滤、分组和指标，限定在调用方的作用域内。服务在文件中声明它自己的数据集；分析目录则定义跨服务的数据集。本页声明一个数据集。数据集查询在 10.0 中是实验性的：它的 API 仍可能改变。

## 声明数据集 {#declare-a-dataset}

数据集是一个名为 `<context>.<dataset>.json` 的 JSON 文件，位于类路径的 `META-INF/wow/dataset/` 下。eShop 的 ordering 服务基于它订单的 ClickHouse 视图声明它的销售数据：

<!-- sample: examples/eshop/ordering/ordering-server/src/main/resources/META-INF/wow/dataset/eshop-ordering.sales.json -->

```json
{
  "title": "Sales",
  "identity": "id",
  "eventTime": "commit_time",
  "scope": { "tenant": "tenant_id", "owner": "owner_id", "space": "space_id" },
  "storage": { "table": "eshop_ordering_order_state_flat" },
  "fields": {
    "id": { "types": ["string"], "nullable": false, "description": "The commit of the order's current state: ref@version." },
    "tenant_id": { "types": ["string"], "nullable": false },
    "aggregate_id": { "types": ["string"], "nullable": false, "description": "The order's id." },
    "owner_id": { "types": ["string"], "nullable": false, "description": "The buyer whose checkout the order is: its owner." },
    "space_id": { "types": ["string"], "nullable": false },
    "version": { "types": ["integer"], "nullable": false },
    "commit_time": { "types": ["string"], "semantic": { "type": "temporal-date" }, "nullable": false },
    "deleted": { "types": ["boolean"], "nullable": false },
    "status": { "types": ["string"], "nullable": false, "description": "SUBMITTED, AWAITING_VALIDATION, STOCK_CONFIRMED, PAID, SHIPPED or CANCELLED." },
    "totalAmount": { "types": ["decimal"], "nullable": false, "description": "The order's total: each line's unit price times its quantity." }
  }
}
```

| 属性 | 声明 |
| --- | --- |
| `title` | 数据集叫什么 |
| `identity` | 标识一行的列 |
| `eventTime` | 相对时间过滤默认读取的列 |
| `scope` | 每一行的租户、所有者和空间所在的列，以及可选的标签：只有作用域包含某一行的调用方才能看到它。改用 `"platform": true` 则表示每个租户都能读取的数据集 |
| `storage` | 后端的绑定：在 ClickHouse 上，是它读取的 `table` |
| `fields` | 每一列：它的 `types`、`nullable`、可选的 `semantic` 类型、`enum` 值和 `description` |

字段也可以用 `"sensitive": {"level": "confidential", "keepSuffix": 4}` 脱敏，与带 `@Sensitive` 的状态字段一样。

工作目录中 `<wow.query.dataset.config-directory>/wow/dataset/`（默认 `config/wow/dataset/`）下的文件，优先于类路径上的同名文件，所以运维人员不用重新构建就能调整一个数据集。

## 行从哪里来 {#where-the-rows-come-from}

装配了数据集后端时才提供数据集；在 10.0 中是 ClickHouse 的后端：类路径上有 `wow-clickhouse`，并设置了 `wow.clickhouse.endpoint` 或声明了 ClickHouse `Client` bean。ClickHouse Sink 由 `wow.clickhouse.sink.enabled: true` 开启，它把每个发布的聚合的提交写入数据集所读取的表，例如 `<context>_<aggregate>_state_flat`，即以状态的标量字段为列的当前状态（[ClickHouse](../operate/clickhouse)）。

## 查询它 {#query-it}

| 请求 | 返回 |
| --- | --- |
| `POST /wow/dataset/<context>/<dataset>/query` | 对数据集的一次查询 |
| `GET /wow/dataset/<context>/<dataset>/query` | 它的能力描述 |
| `GET /wow/dataset` | 调用方能看到的数据集 |

请求体是一个[查询语言](../reference/query-language)写成的查询：例如某个买家按状态分组的销售额，就是一个按 `status` 分组、对 `totalAmount` 求和的 `aggregate`。每个调用方只读取它自己的租户、所有者和空间的行。在 Kotlin 中，`QueryApi.of(caller).datasets(DatasetName.of(context, dataset), rowType)` 执行同样的查询；它与它所服务的聚合一样是实验性的（`@OptIn(ExperimentalWowApi::class)`）。

`wow.query.dataset.max-concurrent`（8）限制一个节点同时运行的数据集查询数量，它们在自己的舱壁下运行，所以分析永远不会占满 Sink 正在写入的数仓；超出的查询会短暂等待，然后返回 `429 wow.query.busy`。已提供但当前无法查询的数据集返回 `409 wow.query.dataset-unavailable`，未知的数据集返回 `404 wow.query.dataset-not-found`。

## 跨服务的数据集 {#datasets-across-services}

关联多个服务的表的数据集，或者由分析师定义和评审、不需要发版的数据集，属于分析目录，它把每个版本发布为一个 ClickHouse 视图，每个节点都以同样的方式提供它（[平台服务](../migrate/platform)）。
